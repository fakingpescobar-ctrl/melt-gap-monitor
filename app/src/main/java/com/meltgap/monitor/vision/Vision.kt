package com.meltgap.monitor.vision

import com.meltgap.monitor.NodeConfig
import com.meltgap.monitor.Zone
import java.nio.ByteBuffer
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
     * One-shot scene scan: finds the two strongest vertical edges (nozzle candidates) in the
     * left and right halves of the frame and derives zones + jet anchors from them.
     */
    fun autoCalibrate(frame: GrayFrame): List<NodeConfig>? {
        val w = frame.width
        val h = frame.height
        if (w < 80 || h < 60) return null

        val y0 = (h * 0.15f).toInt()
        val y1 = (h * 0.85f).toInt()
        val rows = y1 - y0
        if (rows < 10) return null

        val profile = FloatArray(w)
        for (x in 0 until w) {
            var sum = 0
            for (y in y0 until y1) sum += frame.at(x, y)
            profile[x] = sum.toFloat() / rows
        }

        val smooth = FloatArray(w)
        for (i in 0 until w) {
            val a = profile[if (i > 0) i - 1 else 0]
            val b = profile[i]
            val c = profile[if (i < w - 1) i + 1 else w - 1]
            smooth[i] = (a + 2f * b + c) * 0.25f
        }

        val leftEdge = strongestEdge(smooth, (w * 0.03f).toInt(), (w * 0.47f).toInt())
        val rightEdge = strongestEdge(smooth, (w * 0.53f).toInt(), (w * 0.97f).toInt())
        if (leftEdge < 0 || rightEdge < 0) return null
        if (rightEdge - leftEdge < w * 0.08f) return null

        val nodes = ArrayList<NodeConfig>(2)
        for (edge in intArrayOf(leftEdge, rightEdge)) {
            val bodyBrightness = smooth[(edge + 3).coerceAtMost(w - 1)]
            val outsideBrightness = smooth[(edge - 3).coerceAtLeast(0)]
            // Nozzle body extends toward the brighter side of the edge.
            val dir = if (bodyBrightness >= outsideBrightness) 1 else -1
            val bodyFrom = (edge + dir * 2).coerceIn(0, w - 1)
            val bodyTo = (edge + dir * (w * 0.10f).toInt()).coerceIn(0, w - 1)
            val l = minOf(bodyFrom, bodyTo).toFloat() / w
            val r = maxOf(bodyFrom, bodyTo).toFloat() / w
            val anchor = (edge + dir * (w * 0.06f).toInt()).toFloat() / w

            nodes.add(
                NodeConfig(
                    name = "NODE ${nodes.size + 1}",
                    nozzle = Zone(
                        left = l.coerceIn(0f, 0.95f),
                        top = 0.22f,
                        right = (r.coerceAtLeast(l + 0.04f)).coerceIn(0.05f, 1f),
                        bottom = 0.78f
                    ),
                    jetAnchorX = anchor.coerceIn(0.01f, 0.99f),
                    jetHalfWidth = 0.06f
                )
            )
        }
        return nodes
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
        return if (bestVal >= 8f) best else -1
    }

    /** Helpers used by diagnostics only. */
    fun distance(ax: Float, ay: Float, bx: Float, by: Float): Float = hypot(bx - ax, by - ay)
}
