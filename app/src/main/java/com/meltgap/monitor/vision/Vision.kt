package com.meltgap.monitor.vision

import com.meltgap.monitor.NodeConfig
import com.meltgap.monitor.Zone
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/** Single-channel 8-bit frame used for all measurements. */
class GrayFrame(val width: Int, val height: Int, val gray: ByteArray) {
    fun at(x: Int, y: Int): Int = gray[y * width + x].toInt() and 0xFF
}

/** Detected feature: position along X in analysis-frame pixels plus 0..1 confidence. */
data class Detection(val x: Float, val confidence: Float)

/**
 * Pure-Kotlin computer vision. No OpenCV: the scene is simple (dark nozzles, bright melt jets),
 * so column profiles plus gradient analysis are enough for a prototype and stay fast on phone CPUs.
 */
object Vision {

    /** Converts an RGBA_8888 ImageProxy buffer into a GrayFrame. */
    fun toGray(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int
    ): GrayFrame {
        val out = ByteArray(width * height)
        val ps = if (pixelStride <= 0) 4 else pixelStride
        var outIdx = 0
        for (y in 0 until height) {
            var p = y * rowStride
            var x = 0
            while (x < width) {
                val r = buffer.get(p).toInt() and 0xFF
                val g = if (ps > 1) buffer.get(p + 1).toInt() and 0xFF else r
                val b = if (ps > 2) buffer.get(p + 2).toInt() and 0xFF else r
                out[outIdx++] = ((r * 77 + g * 151 + b * 28) shr 8).toByte()
                p += ps
                x++
            }
        }
        return GrayFrame(width, height, out)
    }

    /**
     * Finds the left/right edge of a nozzle inside the zone using the strongest vertical
     * luminance step. Returns X in frame pixels.
     */
    fun detectNozzleEdge(frame: GrayFrame, zone: Zone, minContrast: Float = 10f): Detection? {
        val x0 = (zone.left * frame.width).toInt().coerceIn(0, frame.width - 3)
        val x1 = (zone.right * frame.width).toInt().coerceIn(x0 + 2, frame.width - 1)
        val y0 = (zone.top * frame.height).toInt().coerceIn(0, frame.height - 2)
        val y1 = (zone.bottom * frame.height).toInt().coerceIn(y0 + 1, frame.height - 1)
        val n = x1 - x0
        if (n < 4) return null

        val rows = y1 - y0 + 1
        val profile = FloatArray(n)
        for (x in 0 until n) {
            var sum = 0
            for (y in y0..y1) sum += frame.at(x0 + x, y)
            profile[x] = sum.toFloat() / rows
        }

        // 3-tap smoothing keeps single noisy columns from winning.
        val smooth = FloatArray(n)
        for (i in 0 until n) {
            val a = profile[if (i > 0) i - 1 else 0]
            val b = profile[i]
            val c = profile[if (i < n - 1) i + 1 else n - 1]
            smooth[i] = (a + 2f * b + c) * 0.25f
        }

        val grad = FloatArray(n)
        for (i in 1 until n) grad[i] = abs(smooth[i] - smooth[i - 1])

        var bestIdx = -1
        var bestVal = 0f
        for (i in 1 until n) {
            if (grad[i] > bestVal) {
                bestVal = grad[i]
                bestIdx = i
            }
        }
        if (bestIdx < 1 || bestIdx > n - 2 || bestVal < minContrast) return null

        val l = grad[bestIdx - 1]
        val c = grad[bestIdx]
        val r = grad[bestIdx + 1]
        val denom = l - 2f * c + r
        val sub = if (abs(denom) > 1e-3f) (0.5f * (l - r) / denom).coerceIn(-0.5f, 0.5f) else 0f

        val confidence = ((bestVal - minContrast) / 45f).coerceIn(0f, 1f)
        return Detection(x0 + bestIdx + sub, confidence)
    }

    /**
     * Finds a bright vertical band (melt jet) inside the horizontal search window
     * [anchorX +/- halfWidth], measuring continuity and local contrast against the window mean.
     */
    fun detectJet(
        frame: GrayFrame,
        nozzle: Zone,
        anchorX: Float,
        halfWidth: Float
    ): Detection? {
        val center = (anchorX * frame.width).toInt()
        val hw = max(4, (halfWidth * frame.width).toInt())
        val x0 = (center - hw).coerceIn(0, frame.width - 3)
        val x1 = (center + hw).coerceIn(x0 + 2, frame.width - 1)
        val y0 = (nozzle.top * frame.height).toInt().coerceIn(0, frame.height - 2)
        val y1 = (nozzle.bottom * frame.height).toInt().coerceIn(y0 + 2, frame.height - 1)
        val n = x1 - x0
        val rows = y1 - y0 + 1
        if (n < 3) return null

        var sum = 0L
        var sumSq = 0L
        var count = 0
        for (x in x0..x1) {
            for (y in y0..y1) {
                val v = frame.at(x, y)
                sum += v
                sumSq += (v * v).toLong()
                count++
            }
        }
        if (count == 0) return null
        val mean = sum.toFloat() / count
        val variance = (sumSq.toFloat() / count) - mean * mean
        val sd = sqrt(max(variance, 0f))
        val brightLevel = mean + max(12f, sd * 0.55f)

        val score = FloatArray(n)
        for (x in 0 until n) {
            var columnSum = 0
            var brightRows = 0
            for (y in y0..y1) {
                val v = frame.at(x0 + x, y)
                columnSum += v
                if (v > brightLevel) brightRows++
            }
            val columnMean = columnSum.toFloat() / rows
            val continuity = brightRows.toFloat() / rows
            score[x] = (columnMean - mean) + 60f * continuity
        }

        var bestIdx = 0
        for (i in 1 until n) if (score[i] > score[bestIdx]) bestIdx = i
        val peak = score[bestIdx]
        if (peak < 8f) return null

        // Weighted centroid around the peak for sub-pixel accuracy.
        var weighted = 0f
        var weights = 0f
        val from = max(0, bestIdx - 3)
        val to = minOf(n - 1, bestIdx + 3)
        val threshold = 0.8f * peak
        for (i in from..to) {
            val w = score[i] - threshold
            if (w > 0f) {
                weighted += w * i
                weights += w
            }
        }
        val sub = if (weights > 0f) weighted / weights else bestIdx.toFloat()
        val confidence = ((peak - 8f) / 40f).coerceIn(0f, 1f)
        return Detection(x0 + sub, confidence)
    }

    /**
     * One-shot scene scan.
     *
     * Two strategies, in order of reliability on real footage:
     *  1. bright vertical bands (melt jets) — thin, high-contrast, easy to find;
     *     the nozzle zone is placed just left of each band, which is where the gap is measured;
     *  2. strongest vertical luminance edges, used when the scene has no clear bands.
     */
    fun autoCalibrate(frame: GrayFrame): AutoCalResult {
        val w = frame.width
        val h = frame.height
        if (w < 80 || h < 60) {
            return AutoCalResult(null, "none", "frame too small", "${w}x$h")
        }

        val y0 = (h * 0.12f).toInt().coerceAtLeast(0)
        val y1 = (h * 0.88f).toInt().coerceAtMost(h - 1)
        val rows = (y1 - y0).coerceAtLeast(1)

        val profile = FloatArray(w)
        for (x in 0 until w) {
            var sum = 0
            for (y in y0..y1) sum += frame.at(x, y)
            profile[x] = sum.toFloat() / rows
        }
        val smooth = smooth3(profile)

        val mean = smooth.average().toFloat()
        var variance = 0.0
        for (v in smooth) {
            val d = (v - mean).toDouble()
            variance += d * d
        }
        val sd = sqrt((variance / smooth.size).toFloat())
        val details = String.format(
            Locale.US, "w=%d mean=%.0f sd=%.0f", w, mean, sd
        )

        // --- 1) jet-centric -------------------------------------------------
        val threshold = mean + max(6f, 0.6f * sd)
        val bands = findBrightBands(smooth, threshold, (w * 0.06f).toInt())
        if (bands.size >= 2) {
            val left = bands.first()
            val right = bands.last()
            if (right - left >= w * 0.06f) {
                val nodes = listOf(
                    jetNode(w, "NODE 1", left),
                    jetNode(w, "NODE 2", right)
                )
                return AutoCalResult(
                    nodes,
                    "bright bands",
                    "ok",
                    "$details bands=${bands.joinToString(",")}"
                )
            }
        }

        // --- 2) edge fallback ----------------------------------------------
        val leftEdge = strongestEdge(smooth, (w * 0.03f).toInt(), (w * 0.47f).toInt())
        val rightEdge = strongestEdge(smooth, (w * 0.53f).toInt(), (w * 0.97f).toInt())
        if (leftEdge < 0 || rightEdge < 0) {
            return AutoCalResult(
                null,
                "none",
                "no vertical structures (bands=${bands.size})",
                details
            )
        }
        if (rightEdge - leftEdge < w * 0.06f) {
            return AutoCalResult(null, "none", "structures too close", details)
        }
        val nodes = listOf(
            edgeNode(w, "NODE 1", leftEdge, smooth),
            edgeNode(w, "NODE 2", rightEdge, smooth)
        )
        return AutoCalResult(
            nodes,
            "vertical edges",
            "ok",
            "$details left=$leftEdge right=$rightEdge"
        )
    }

    private fun smooth3(src: FloatArray): FloatArray {
        val out = FloatArray(src.size)
        for (i in src.indices) {
            val a = src[if (i > 0) i - 1 else 0]
            val b = src[i]
            val c = src[if (i < src.size - 1) i + 1 else src.size - 1]
            out[i] = (a + 2f * b + c) * 0.25f
        }
        return out
    }

    /** Local maxima of the column profile above [threshold], strongest first, min separation. */
    private fun findBrightBands(profile: FloatArray, threshold: Float, minSeparation: Int): List<Int> {
        val peaks = ArrayList<Pair<Int, Float>>()
        for (i in 1 until profile.size - 1) {
            if (profile[i] > threshold &&
                profile[i] >= profile[i - 1] &&
                profile[i] > profile[i + 1]
            ) {
                peaks.add(Pair(i, profile[i]))
            }
        }
        if (peaks.isEmpty()) return emptyList()

        val sep = minSeparation.coerceAtLeast(6)
        val chosen = ArrayList<Int>()
        for (peak in peaks.sortedByDescending { it.second }) {
            if (chosen.none { abs(it - peak.first) < sep }) chosen.add(peak.first)
            if (chosen.size >= 4) break
        }
        return chosen.sorted()
    }

    /** Zone sits left of the jet, jet axis on the band. */
    private fun jetNode(w: Int, name: String, jetX: Int): NodeConfig {
        val left = (jetX - w * 0.12f).coerceAtLeast(0f)
        val right = (jetX - w * 0.01f).coerceAtLeast(left + w * 0.03f)
        return NodeConfig(
            name = name,
            nozzle = Zone(
                left = (left / w).coerceIn(0f, 0.94f),
                top = 0.25f,
                right = (right / w).coerceIn(0.06f, 1f),
                bottom = 0.75f
            ),
            jetAnchorX = (jetX.toFloat() / w).coerceIn(0.01f, 0.99f),
            jetHalfWidth = 0.05f
        )
    }

    /** Zone grows from the edge toward the brighter side (nozzle body). */
    private fun edgeNode(w: Int, name: String, edge: Int, profile: FloatArray): NodeConfig {
        val bodyBrightness = profile[(edge + 3).coerceAtMost(w - 1)]
        val outsideBrightness = profile[(edge - 3).coerceAtLeast(0)]
        val dir = if (bodyBrightness >= outsideBrightness) 1 else -1

        val from = (edge + dir * 2).coerceIn(0, w - 1)
        val to = (edge + dir * (w * 0.10f).toInt()).coerceIn(0, w - 1)
        val left = minOf(from, to).toFloat() / w
        val right = maxOf(maxOf(from, to).toFloat() / w, left + 0.03f)

        return NodeConfig(
            name = name,
            nozzle = Zone(
                left = left.coerceIn(0f, 0.94f),
                top = 0.25f,
                right = right.coerceIn(0.06f, 1f),
                bottom = 0.75f
            ),
            jetAnchorX = ((edge + dir * (w * 0.06f)).toFloat() / w).coerceIn(0.01f, 0.99f),
            jetHalfWidth = 0.05f
        )
    }

    data class AutoCalResult(
        val nodes: List<NodeConfig>?,
        val method: String,
        val reason: String,
        val details: String
    ) {
        val ok: Boolean get() = nodes != null && nodes.size == 2
    }

    private fun strongestEdge(smoothed: FloatArray, from: Int, to: Int): Int {
        if (to - from < 6) return -1
        var best = -1
        var bestVal = 0f
        for (i in max(1, from) until minOf(to, smoothed.size - 1)) {
            val v = abs(smoothed[i] - smoothed[i - 1])
            if (v > bestVal) {
                bestVal = v
                best = i
            }
        }
        return if (bestVal >= 6f) best else -1
    }

    /** Helpers used by diagnostics only. */
    fun distance(ax: Float, ay: Float, bx: Float, by: Float): Float = hypot(bx - ax, by - ay)
}
