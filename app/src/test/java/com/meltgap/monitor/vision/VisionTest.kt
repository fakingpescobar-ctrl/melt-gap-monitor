package com.meltgap.monitor.vision

import com.meltgap.monitor.Zone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * Synthetic-scene tests for the detection core. Runs on a plain JVM (no emulator):
 * the algorithms only depend on a grayscale buffer.
 */
class VisionTest {

    private fun blank(width: Int, height: Int, level: Int): GrayFrame =
        GrayFrame(width, height, ByteArray(width * height) { level.toByte() })

    private fun GrayFrame.fill(x0: Int, x1: Int, level: Int) {
        for (y in 0 until height) {
            for (x in x0 until x1) {
                gray[y * width + x] = level.toByte()
            }
        }
    }

    @Test
    fun nozzleEdgeIsFoundAtStep() {
        val frame = blank(160, 120, 40)
        frame.fill(90, 120, 200)

        // Zone covers x = 80..136, the step sits at x = 90.
        val zone = Zone(0.5f, 0.3f, 0.85f, 0.7f)
        val detection = Vision.detectNozzleEdge(frame, zone)

        assertNotNull(detection)
        assertEquals(90f, detection!!.x, 1.5f)
        assertTrue(detection.confidence > 0.5f)
    }

    @Test
    fun nozzleEdgeMissingOnFlatScene() {
        val frame = blank(160, 120, 40)
        val zone = Zone(0.2f, 0.3f, 0.8f, 0.7f)
        assertNull(Vision.detectNozzleEdge(frame, zone))
    }

    @Test
    fun jetIsFoundAsBrightColumn() {
        val frame = blank(160, 120, 30)
        frame.fill(70, 74, 220)

        val zone = Zone(0.4f, 0.2f, 0.6f, 0.8f)
        val detection = Vision.detectJet(frame, zone, anchorX = 0.45f, halfWidth = 0.1f)

        assertNotNull(detection)
        // Column band 70..73, centroid lands around 71.5.
        assertEquals(71.5f, detection!!.x, 1.2f)
    }

    @Test
    fun jetIsMissingOnFlatScene() {
        val frame = blank(160, 120, 30)
        val zone = Zone(0.4f, 0.2f, 0.6f, 0.8f)
        assertNull(Vision.detectJet(frame, zone, anchorX = 0.5f, halfWidth = 0.1f))
    }

    @Test
    fun autoCalibrationFindsTwoNozzles() {
        val frame = blank(320, 240, 20)
        frame.fill(60, 100, 210)
        frame.fill(220, 260, 210)

        val result = Vision.autoCalibrate(frame)

        assertTrue("auto-cal should succeed: ${result.reason}", result.ok)
        val nodes = result.nodes!!
        assertEquals(2, nodes.size)
        assertTrue("left nozzle should stay in the left half", nodes[0].nozzle.left < 0.5f)
        assertTrue("right nozzle should stay in the right half", nodes[1].nozzle.left > 0.5f)
        assertTrue(nodes[0].nozzle.right > nodes[0].nozzle.left)
    }

    @Test
    fun autoCalibrationFailsOnFlatScene() {
        val frame = blank(320, 240, 90)
        val result = Vision.autoCalibrate(frame)
        assertEquals(false, result.ok)
        assertNull(result.nodes)
    }

    @Test
    fun rgbaBufferIsConvertedToGray() {
        val width = 4
        val height = 2
        val buffer = ByteBuffer.allocate(width * height * 4)
        // Row 0: white, black, red, green (RGB-ish luma expected)
        buffer.put(255.toByte()).put(255.toByte()).put(255.toByte()).put(255.toByte())
        buffer.put(0.toByte()).put(0.toByte()).put(0.toByte()).put(255.toByte())
        buffer.put(255.toByte()).put(0.toByte()).put(0.toByte()).put(255.toByte())
        buffer.put(0.toByte()).put(255.toByte()).put(0.toByte()).put(255.toByte())
        // Row 1: all mid gray
        repeat(width * 4) { buffer.put(128.toByte()) }
        buffer.rewind()

        val frame = Vision.toGray(buffer, width, height, rowStride = width * 4, pixelStride = 4)

        assertEquals(255, frame.at(0, 0))
        assertEquals(0, frame.at(1, 0))
        assertEquals(76, frame.at(2, 0))
        assertEquals(150, frame.at(3, 0))
        assertEquals(128, frame.at(0, 1))
    }

    @Test
    fun gapMathUsesFrameWidthScale() {
        // 640 px wide frame that is 100 mm wide -> 0.15625 mm per pixel.
        val mmPerImageWidth = 100f
        val frameWidth = 640
        val gapPx = 32f
        val gapMm = gapPx * mmPerImageWidth / frameWidth
        assertEquals(5.0f, gapMm, 0.001f)
    }
}
