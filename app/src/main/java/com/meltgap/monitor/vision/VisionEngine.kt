package com.meltgap.monitor.vision

import android.os.SystemClock
import com.meltgap.monitor.AppSettings
import com.meltgap.monitor.diag.DiagLog
import kotlin.math.abs
import java.util.Locale

/**
 * Runs per-frame analysis on the camera executor thread and publishes an immutable snapshot
 * for the overlay. Temporal smoothing (exponential moving average) kills the jitter.
 */
class VisionEngine(private val settingsProvider: () -> AppSettings) {

    data class NodeResult(
        val nozzleX: Float = -1f,
        val nozzleConfidence: Float = 0f,
        val jetX: Float = -1f,
        val jetConfidence: Float = 0f,
        val gapPx: Float = -1f,
        val gapMm: Float = 0f,
        val hasGap: Boolean = false
    )

    data class Snapshot(
        val frameW: Int,
        val frameH: Int,
        val rotation: Int,
        val nodes: List<NodeResult>,
        val processingMs: Long,
        val autoCalibrated: Boolean
    )

    @Volatile
    var snapshot: Snapshot? = null
        private set

    /** Called from the analysis thread after each frame; used to trigger a redraw. */
    @Volatile
    var onAnalyzed: (() -> Unit)? = null

    @Volatile
    private var autoCalRequested = false

    private var lastMetricsLogAt = 0L

    private val smoothNozzle = floatArrayOf(-1f, -1f)
    private val smoothJet = floatArrayOf(-1f, -1f)
    private var smoothedRotation = -1

    fun requestAutoCalibration() {
        autoCalRequested = true
    }

    fun resetSmoothing() {
        synchronized(smoothNozzle) {
            smoothNozzle.fill(-1f)
            smoothJet.fill(-1f)
            smoothedRotation = -1
        }
    }

    fun process(frame: GrayFrame, rotation: Int) {
        val started = SystemClock.elapsedRealtime()
        val settings = settingsProvider()

        if (smoothedRotation != rotation) {
            resetSmoothing()
            smoothedRotation = rotation
        }

        if (autoCalRequested) {
            autoCalRequested = false
            val calibrated = runCatching { Vision.autoCalibrate(frame) }.getOrNull()
            if (calibrated != null && calibrated.size == settings.nodes.size) {
                synchronized(settings.nodes) {
                    for (i in settings.nodes.indices) {
                        settings.nodes[i].nozzle = calibrated[i].nozzle
                        settings.nodes[i].jetAnchorX = calibrated[i].jetAnchorX
                        settings.nodes[i].jetHalfWidth = calibrated[i].jetHalfWidth
                    }
                }
                settings.autoCalOk = true
                runCatching { settings.save() }
                resetSmoothing()
                DiagLog.i("AUTOCAL", "OK — " + calibrated.joinToString("; ") {
                    String.format(
                        Locale.US,
                        "zone %.2f..%.2f axis %.2f",
                        it.nozzle.left, it.nozzle.right, it.jetAnchorX
                    )
                })
            } else {
                settings.autoCalOk = false
                DiagLog.w("AUTOCAL", "FAILED — no strong vertical edges in frame ${frame.width}x${frame.height}")
            }
        }

        val nodes = synchronized(settings.nodes) { settings.nodes.map { it } }
        val results = ArrayList<NodeResult>(nodes.size)
        var autoOk = settings.autoCalOk

        synchronized(smoothNozzle) {
            for (i in nodes.indices) {
                val node = nodes[i]
                val nozzle = runCatching { Vision.detectNozzleEdge(frame, node.nozzle) }.getOrNull()
                val jet = runCatching {
                    Vision.detectJet(frame, node.nozzle, node.jetAnchorX, node.jetHalfWidth)
                }.getOrNull()

                val nozzleX = smooth(i, smoothNozzle, nozzle?.x)
                val jetX = smooth(i, smoothJet, jet?.x)

                var gapPx = -1f
                var hasGap = false
                if (nozzleX > 0f && jetX > 0f) {
                    gapPx = abs(jetX - nozzleX)
                    hasGap = gapPx > 0.5f
                }
                val gapMm = if (hasGap && settings.mmPerImageWidth > 0f) {
                    gapPx * settings.mmPerImageWidth / frame.width
                } else 0f

                if (nozzle == null && jet == null) autoOk = false

                results.add(
                    NodeResult(
                        nozzleX = nozzleX,
                        nozzleConfidence = nozzle?.confidence ?: 0f,
                        jetX = jetX,
                        jetConfidence = jet?.confidence ?: 0f,
                        gapPx = gapPx,
                        gapMm = gapMm,
                        hasGap = hasGap
                    )
                )
            }
        }

        snapshot = Snapshot(
            frameW = frame.width,
            frameH = frame.height,
            rotation = rotation,
            nodes = results,
            processingMs = SystemClock.elapsedRealtime() - started,
            autoCalibrated = autoOk
        )
        logDiagnostics(snapshot!!)
        onAnalyzed?.invoke()
    }

    private val gapState = ArrayList<Boolean>()

    /**
     * Low-volume diagnostics: one line per second with the numbers, plus an event line whenever
     * a node acquires or loses its jet. This is what makes remote debugging possible.
     */
    private fun logDiagnostics(snap: Snapshot) {
        if (gapState.size != snap.nodes.size) {
            gapState.clear()
            repeat(snap.nodes.size) { gapState.add(false) }
        }
        snap.nodes.forEachIndexed { index, node ->
            val previous = gapState[index]
            if (previous != node.hasGap) {
                gapState[index] = node.hasGap
                if (node.hasGap) {
                    DiagLog.i(
                        "VISION",
                        String.format(Locale.US, "NODE %d jet ACQUIRED at x=%.0f (conf %.2f)",
                            index + 1, node.jetX, node.jetConfidence)
                    )
                } else {
                    DiagLog.w("VISION", "NODE ${index + 1} jet LOST")
                }
            }
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastMetricsLogAt < 1000L) return
        lastMetricsLogAt = now

        val text = snap.nodes.mapIndexed { index, node ->
            String.format(
                Locale.US,
                "N%d nozzle=%.0f/%.2f jet=%.0f/%.2f gap=%s",
                index + 1,
                node.nozzleX,
                node.nozzleConfidence,
                node.jetX,
                node.jetConfidence,
                if (node.hasGap) String.format(Locale.US, "%.0fpx %.1fmm", node.gapPx, node.gapMm) else "none"
            )
        }.joinToString(" | ")
        DiagLog.d("VISION", "$text  ${snap.processingMs}ms")
    }

    private fun smooth(index: Int, store: FloatArray, raw: Float?): Float {
        if (index >= store.size) return raw ?: -1f
        val previous = store[index]
        return when {
            raw == null || raw <= 0f -> {
                // Keep last known value but let constant smoothing fade it out slowly.
                if (previous > 0f) previous * 0.995f else -1f
            }
            previous <= 0f -> {
                store[index] = raw
                raw
            }
            else -> {
                val next = previous + 0.25f * (raw - previous)
                store[index] = next
                next
            }
        }
    }
}
