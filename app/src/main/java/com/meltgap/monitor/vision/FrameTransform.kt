package com.meltgap.monitor.vision

import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.min

/**
 * Maps between the normalized coordinates of the raw analysis frame (unrotated sensor buffer)
 * and the pixel coordinates of the on-screen view.
 *
 * PreviewView is configured with scaleType=fitCenter, so the image is letterboxed inside the
 * view. Everything here mirrors that geometry, including the image rotation reported by CameraX.
 */
class FrameTransform(
    val frameW: Int,
    val frameH: Int,
    rotationDegrees: Int,
    viewW: Float,
    viewH: Float
) {
    private val rot = ((rotationDegrees % 360) + 360) % 360

    /** Size of the upright (rotated) image. */
    val dispW: Float = if (rot % 180 == 0) frameW.toFloat() else frameH.toFloat()
    val dispH: Float = if (rot % 180 == 0) frameH.toFloat() else frameW.toFloat()

    private val scale: Float = min(viewW / dispW, viewH / dispH)
    private val offX: Float = (viewW - dispW * scale) * 0.5f
    private val offY: Float = (viewH - dispH * scale) * 0.5f

    val imageRect: RectF
        get() = RectF(offX, offY, offX + dispW * scale, offY + dispH * scale)

    fun toView(ix: Float, iy: Float): PointF {
        val dx: Float
        val dy: Float
        when (rot) {
            90 -> {
                dx = frameH - iy
                dy = ix
            }
            180 -> {
                dx = frameW - ix
                dy = frameH - iy
            }
            270 -> {
                dx = iy
                dy = frameW - ix
            }
            else -> {
                dx = ix
                dy = iy
            }
        }
        return PointF(offX + dx * scale, offY + dy * scale)
    }

    fun toViewNorm(nx: Float, ny: Float): PointF = toView(nx * frameW, ny * frameH)

    /** View pixels -> normalized image coordinates. */
    fun toImageNorm(vx: Float, vy: Float): PointF {
        if (scale <= 0f) return PointF(0f, 0f)
        val dx = (vx - offX) / scale
        val dy = (vy - offY) / scale
        val ix: Float
        val iy: Float
        when (rot) {
            90 -> {
                ix = dy
                iy = frameH - dx
            }
            180 -> {
                ix = frameW - dx
                iy = frameH - dy
            }
            270 -> {
                ix = frameW - dy
                iy = dx
            }
            else -> {
                ix = dx
                iy = dy
            }
        }
        return PointF(ix / frameW, iy / frameH)
    }

    /** Length of a segment given in normalized image units, returned in image pixels. */
    fun normToPixelLength(nx1: Float, ny1: Float, nx2: Float, ny2: Float): Float {
        val ax = nx1 * frameW
        val ay = ny1 * frameH
        val bx = nx2 * frameW
        val by = ny2 * frameH
        return kotlin.math.hypot(bx - ax, by - ay)
    }
}
