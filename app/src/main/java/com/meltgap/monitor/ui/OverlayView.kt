package com.meltgap.monitor.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import com.meltgap.monitor.AppSettings
import com.meltgap.monitor.Zone
import com.meltgap.monitor.diag.DiagLog
import com.meltgap.monitor.vision.FrameTransform
import com.meltgap.monitor.vision.VisionEngine
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Transparent HUD drawn on top of the camera preview: nozzle zones, jet axes, gap segments,
 * status panel and the manual-calibration gesture handling.
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Mode { RUN, CALIBRATE, SCALE_PICK }

    var engine: VisionEngine? = null
    var settings: AppSettings? = null
        set(value) {
            field = value
            invalidate()
        }

    var mode: Mode = Mode.RUN
        set(value) {
            field = value
            if (value != Mode.CALIBRATE) {
                draggingNode = -1
                dragKind = DragKind.NONE
            }
            invalidate()
        }

    var cameraActive: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /** Draws the in-app log panel (tap LOG in the control bar). */
    var showLog: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /** Called when the user finished editing zones so the activity can persist them. */
    var onZonesChanged: (() -> Unit)? = null

    /** Called with the pixel distance of a two-point scale calibration in the analysis frame. */
    var onScalePicked: ((Float) -> Unit)? = null

    private enum class DragKind { NONE, MOVE, CORNER_TL, CORNER_TR, CORNER_BL, CORNER_BR, ANCHOR }

    private var draggingNode = -1
    private var dragKind = DragKind.NONE
    private var dragTouch = PointF()

    private var scaleA: PointF? = null
    private var scaleB: PointF? = null

    private val density = resources.displayMetrics

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, density)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, density)

    private val zoneFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(28, 179, 136, 255)
    }
    private val zoneStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = Color.parseColor("#B388FF")
    }
    private val zoneStrokeActive = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        color = Color.parseColor("#E0D0FF")
    }
    private val jetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = Color.parseColor("#40E0FF")
    }
    private val jetBadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = Color.parseColor("#FF4D4D")
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(6f), dp(5f)), 0f)
    }
    private val anchorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.argb(110, 255, 255, 255)
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(4f), dp(8f)), 0f)
    }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = Color.parseColor("#FFD54F")
    }
    private val gapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        color = Color.parseColor("#4CD964")
    }
    private val gapBadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        color = Color.parseColor("#FFB300")
    }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.argb(70, 255, 255, 255)
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = Color.BLACK
    }
    private val hudText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sp(12f)
        typeface = Typeface.MONOSPACE
        setShadowLayer(dp(3f), 0f, 0f, Color.BLACK)
    }
    private val hudBold = Paint(hudText).apply {
        textSize = sp(15f)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val hudBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(170, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255)
        textSize = sp(13f)
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
        setShadowLayer(dp(3f), 0f, 0f, Color.BLACK)
    }
    private val logPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B9F6CA")
        textSize = sp(9f)
        typeface = Typeface.MONOSPACE
    }

    fun beginScalePicking() {
        scaleA = null
        scaleB = null
        mode = Mode.SCALE_PICK
    }

    fun cancelScalePicking() {
        scaleA = null
        scaleB = null
        if (mode == Mode.SCALE_PICK) mode = Mode.CALIBRATE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val settings = this.settings ?: return
        val snapshot = engine?.snapshot

        if (snapshot == null) {
            drawHint(canvas, "WAITING FOR CAMERA…")
            return
        }

        val tf = FrameTransform(
            snapshot.frameW,
            snapshot.frameH,
            snapshot.rotation,
            width.toFloat(),
            height.toFloat()
        )

        canvas.drawRect(tf.imageRect, framePaint)

        for (i in settings.nodes.indices) {
            val node = settings.nodes[i]
            val result = snapshot.nodes.getOrNull(i)
            val zoneRect = rectFor(tf, node.nozzle)
            val isActive = draggingNode == i

            canvas.drawRect(zoneRect, zoneFill)
            canvas.drawRect(zoneRect, if (isActive) zoneStrokeActive else zoneStroke)

            drawLabel(canvas, node.name, zoneRect.left, zoneRect.top)

            // Jet search window (dashed anchor line in calibration mode only).
            if (mode == Mode.CALIBRATE) {
                val ax = tf.toViewNorm(node.jetAnchorX, 0f).x
                canvas.drawLine(ax, zoneRect.top, ax, zoneRect.bottom, anchorPaint)
            }

            if (result != null) {
                if (result.nozzleX > 0f) {
                    val top = tf.toView(result.nozzleX, node.nozzle.top * snapshot.frameH)
                    val bottom = tf.toView(result.nozzleX, node.nozzle.bottom * snapshot.frameH)
                    canvas.drawLine(top.x, top.y, bottom.x, bottom.y, edgePaint)
                }
                if (result.jetX > 0f) {
                    val top = tf.toView(result.jetX, node.nozzle.top * snapshot.frameH)
                    val bottom = tf.toView(result.jetX, node.nozzle.bottom * snapshot.frameH)
                    val paint = if (result.jetConfidence > 0.15f) jetPaint else jetBadPaint
                    canvas.drawLine(top.x, top.y, bottom.x, bottom.y, paint)
                }
                if (result.hasGap && result.nozzleX > 0f && result.jetX > 0f) {
                    val yMid = (node.nozzle.top + node.nozzle.bottom) * 0.5f
                    drawGap(canvas, tf, result.nozzleX, result.jetX, yMid, result.gapMm, settings, result.gapPx)
                }
            }

            if (mode == Mode.CALIBRATE && isActive) {
                drawHandles(canvas, zoneRect)
            }
        }

        drawScalePoints(canvas, tf)

        drawHud(canvas, settings, snapshot, tf)

        when (mode) {
            Mode.CALIBRATE -> drawHint(
                canvas,
                "CALIBRATION • DRAG ZONE = MOVE • CORNERS = RESIZE • DASHED LINE = JET AXIS"
            )
            Mode.SCALE_PICK -> drawHint(
                canvas,
                if (scaleA == null) "SCALE: TAP FIRST REFERENCE POINT"
                else "SCALE: TAP SECOND REFERENCE POINT"
            )
            Mode.RUN -> Unit
        }

        if (!cameraActive) drawHint(canvas, "CAMERA OFFLINE")

        if (showLog) drawLogPanel(canvas)
    }

    private fun drawLogPanel(canvas: Canvas) {
        val entries = DiagLog.recent(9)
        if (entries.isEmpty()) return
        val pad = dp(8f)
        val lineH = logPaint.textSize * 1.30f
        val maxW = width * 0.62f
        val height2 = lineH * entries.size + pad * 2f
        val bottom = height - dp(76f)
        val top = (bottom - height2).coerceAtLeast(dp(60f))
        canvas.drawRect(pad, top, pad + maxW, top + height2, hudBg)
        var y = top + pad - logPaint.fontMetrics.ascent
        for (entry in entries) {
            canvas.drawText(
                fitText(logPaint, entry.line(), maxW - pad * 2f),
                pad * 1.5f,
                y,
                logPaint
            )
            y += lineH
        }
    }

    private fun fitText(paint: Paint, text: String, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 4 && paint.measureText(text, 0, end) > maxWidth) end--
        return text.substring(0, end) + "…"
    }

    private fun rectFor(tf: FrameTransform, zone: Zone): RectF {
        val a = tf.toViewNorm(zone.left, zone.top)
        val b = tf.toViewNorm(zone.right, zone.bottom)
        return RectF(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))
    }

    private fun drawLabel(canvas: Canvas, text: String, x: Float, y: Float) {
        val pad = dp(4f)
        val fm = hudText.fontMetrics
        val w = hudText.measureText(text) + pad * 2
        val h = (fm.descent - fm.ascent) + pad * 2
        val top = (y - h).coerceAtLeast(0f)
        canvas.drawRect(x, top, x + w, top + h, labelBg)
        canvas.drawText(text, x + pad, top + pad - fm.ascent, hudText)
    }

    private fun drawGap(
        canvas: Canvas,
        tf: FrameTransform,
        nozzleX: Float,
        jetX: Float,
        yMidNorm: Float,
        gapMm: Float,
        settings: AppSettings,
        gapPx: Float
    ) {
        val a = tf.toView(nozzleX, yMidNorm * (tf.frameH.toFloat()))
        val b = tf.toView(jetX, yMidNorm * (tf.frameH.toFloat()))
        val value = if (settings.isScaleCalibrated()) {
            String.format(Locale.US, "%.1f mm", gapMm)
        } else {
            String.format(Locale.US, "%.0f px", gapPx)
        }
        val deviation = if (settings.isScaleCalibrated()) gapMm - settings.targetGapMm else 0f
        val inTolerance = settings.isScaleCalibrated() && abs(deviation) <= settings.toleranceMm
        val paint = if (inTolerance) gapPaint else gapBadPaint

        canvas.drawLine(a.x, a.y, b.x, b.y, paint)
        val tick = dp(9f)
        canvas.drawLine(a.x, a.y - tick, a.x, a.y + tick, paint)
        canvas.drawLine(b.x, b.y - tick, b.x, b.y + tick, paint)

        val label = value
        val tw = hudBold.measureText(label)
        val cx = (a.x + b.x) * 0.5f
        val cy = (a.y + b.y) * 0.5f - dp(12f)
        val fm = hudBold.fontMetrics
        val pad = dp(4f)
        canvas.drawRect(
            cx - tw / 2 - pad, cy + fm.ascent - pad,
            cx + tw / 2 + pad, cy + fm.descent + pad,
            labelBg
        )
        canvas.drawText(label, cx - tw / 2, cy, hudBold)
    }

    private fun drawScalePoints(canvas: Canvas, tf: FrameTransform) {
        val a = scaleA ?: return
        val av = tf.toViewNorm(a.x, a.y)
        canvas.drawCircle(av.x, av.y, dp(8f), handleFill)
        canvas.drawCircle(av.x, av.y, dp(8f), handleStroke)
        val b = scaleB ?: return
        val bv = tf.toViewNorm(b.x, b.y)
        canvas.drawCircle(bv.x, bv.y, dp(8f), handleFill)
        canvas.drawCircle(bv.x, bv.y, dp(8f), handleStroke)
        canvas.drawLine(av.x, av.y, bv.x, bv.y, gapPaint)
    }

    private fun drawHandles(canvas: Canvas, rect: RectF) {
        val s = dp(9f)
        for (p in cornerPoints(rect)) {
            canvas.drawRect(p.x - s, p.y - s, p.x + s, p.y + s, handleFill)
            canvas.drawRect(p.x - s, p.y - s, p.x + s, p.y + s, handleStroke)
        }
    }

    private fun cornerPoints(rect: RectF): List<PointF> = listOf(
        PointF(rect.left, rect.top),
        PointF(rect.right, rect.top),
        PointF(rect.left, rect.bottom),
        PointF(rect.right, rect.bottom)
    )

    private fun drawHud(
        canvas: Canvas,
        settings: AppSettings,
        snapshot: VisionEngine.Snapshot,
        tf: FrameTransform
    ) {
        val pad = dp(10f)
        val lineH = hudText.textSize * 1.35f

        val left = ArrayList<String>(6)
        left.add("CAMERA STREAM: " + if (cameraActive) "ACTIVE" else "OFFLINE")
        left.add("AUTO-CAL: " + if (snapshot.autoCalibrated) "OK" else "NEEDS CHECK")
        left.add("MODE: " + if (mode == Mode.RUN) "RUN" else "CALIBRATION")
        left.add(
            if (settings.isScaleCalibrated())
                String.format(
                    Locale.US,
                    "SCALE: %.3f mm/px • VIEW %.1f mm",
                    settings.mmPerPixel(snapshot.frameW),
                    settings.mmPerImageWidth
                )
            else "SCALE: NOT SET (press SCALE)"
        )
        left.add(
            String.format(
                Locale.US,
                "ENGINE: %d ms/frame • %dx%d",
                snapshot.processingMs,
                snapshot.frameW,
                snapshot.frameH
            )
        )

        val leftW = left.maxOf { hudText.measureText(it) } + pad * 2
        val leftH = lineH * left.size + pad * 1.2f

        val right = ArrayList<String>(3)
        right.add(
            String.format(
                Locale.US,
                "TARGET: %.1f mm   TOL: ±%.1f mm",
                settings.targetGapMm,
                settings.toleranceMm
            )
        )
        for (i in settings.nodes.indices) {
            val r = snapshot.nodes.getOrNull(i) ?: continue
            if (!r.hasGap) {
                right.add(String.format(Locale.US, "GAP %d: --- (no jet)", i + 1))
                continue
            }
            if (settings.isScaleCalibrated()) {
                val dev = r.gapMm - settings.targetGapMm
                right.add(
                    String.format(
                        Locale.US,
                        "GAP %d: %.1f mm  Δ%+.1f   %s",
                        i + 1,
                        r.gapMm,
                        dev,
                        commandFor(dev, settings)
                    )
                )
            } else {
                right.add(String.format(Locale.US, "GAP %d: %.0f px (unscaled)", i + 1, r.gapPx))
            }
        }
        right.add("CMD: EMULATED (no relays)")

        val rightW = right.maxOf { hudText.measureText(it) } + pad * 2
        val rightH = lineH * right.size + pad * 1.2f

        val top = tf.imageRect.top + pad
        canvas.drawRect(pad, top, pad + leftW, top + leftH, hudBg)
        var y = top + pad * 0.6f - hudText.fontMetrics.ascent
        for (line in left) {
            canvas.drawText(line, pad * 1.6f, y, hudText)
            y += lineH
        }

        val rx = width - pad - rightW
        canvas.drawRect(rx, top, rx + rightW, top + rightH, hudBg)
        var ry = top + pad * 0.6f - hudText.fontMetrics.ascent
        for ((index, line) in right.withIndex()) {
            canvas.drawText(line, rx + pad, ry, hudText)
            ry += lineH
            if (index == 0) ry += dp(2f)
        }
    }

    private fun commandFor(deviation: Float, settings: AppSettings): String = when {
        abs(deviation) <= settings.toleranceMm -> "HOLD"
        deviation < 0f -> String.format(Locale.US, "SHIFT RIGHT %.2f", abs(deviation))
        else -> String.format(Locale.US, "SHIFT LEFT %.2f", deviation)
    }

    private fun drawHint(canvas: Canvas, text: String) {
        val y = (height * 0.5f + dp(80f)).coerceAtMost(height - dp(80f))
        canvas.drawText(text, width / 2f, y, hintPaint)
    }

    // ------------------------------------------------------------------ touch

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val settings = this.settings ?: return false
        val snapshot = engine?.snapshot ?: return false
        val tf = FrameTransform(
            snapshot.frameW,
            snapshot.frameH,
            snapshot.rotation,
            width.toFloat(),
            height.toFloat()
        )

        when (mode) {
            Mode.SCALE_PICK -> {
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    handleScaleTap(tf, event.x, event.y)
                }
                return true
            }
            Mode.CALIBRATE -> return handleCalibrationTouch(tf, settings, event)
            Mode.RUN -> return false
        }
    }

    private fun handleScaleTap(tf: FrameTransform, x: Float, y: Float) {
        val norm = tf.toImageNorm(x, y)
        if (scaleA == null) {
            scaleA = norm
            scaleB = null
            invalidate()
            return
        }
        scaleB = norm
        val a = scaleA ?: return
        val b = norm
        val px = tf.normToPixelLength(a.x, a.y, b.x, b.y)
        invalidate()
        onScalePicked?.invoke(px)
    }

    private fun handleCalibrationTouch(tf: FrameTransform, settings: AppSettings, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val node = pickHandle(settings, tf, event.x, event.y)
                draggingNode = node.first
                dragKind = node.second
                dragTouch.set(event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingNode < 0 || dragKind == DragKind.NONE) return true
                val node = settings.nodes.getOrNull(draggingNode) ?: return true
                val norm = tf.toImageNorm(event.x, event.y)
                applyDrag(node, norm)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (draggingNode >= 0) {
                    settings.nodes.getOrNull(draggingNode)?.nozzle?.clampInside()
                    onZonesChanged?.invoke()
                }
                draggingNode = -1
                dragKind = DragKind.NONE
                invalidate()
                return true
            }
        }
        return false
    }

    private fun applyDrag(node: com.meltgap.monitor.NodeConfig, norm: PointF) {
        val z = node.nozzle
        when (dragKind) {
            DragKind.MOVE -> {
                val dx = norm.x - dragTouchNorm.x
                val dy = norm.y - dragTouchNorm.y
                if (abs(dx) + abs(dy) < 1e-5f) return
                z.left = (z.left + dx).coerceIn(0f, 0.95f - z.width)
                z.top = (z.top + dy).coerceIn(0f, 0.95f - z.height)
                z.right = z.left + z.width.coerceAtLeast(0.05f)
                z.bottom = z.top + z.height.coerceAtLeast(0.05f)
                // moving a zone drags its jet axis along with it
                node.jetAnchorX = (node.jetAnchorX + dx).coerceIn(0.01f, 0.99f)
                dragTouchNorm.set(norm.x, norm.y)
            }
            DragKind.CORNER_TL -> {
                z.left = norm.x.coerceIn(0f, z.right - 0.05f)
                z.top = norm.y.coerceIn(0f, z.bottom - 0.05f)
            }
            DragKind.CORNER_TR -> {
                z.right = norm.x.coerceIn(z.left + 0.05f, 1f)
                z.top = norm.y.coerceIn(0f, z.bottom - 0.05f)
            }
            DragKind.CORNER_BL -> {
                z.left = norm.x.coerceIn(0f, z.right - 0.05f)
                z.bottom = norm.y.coerceIn(z.top + 0.05f, 1f)
            }
            DragKind.CORNER_BR -> {
                z.right = norm.x.coerceIn(z.left + 0.05f, 1f)
                z.bottom = norm.y.coerceIn(z.top + 0.05f, 1f)
            }
            DragKind.ANCHOR -> node.jetAnchorX = norm.x.coerceIn(0.01f, 0.99f)
            DragKind.NONE -> Unit
        }
    }

    private val dragTouchNorm = PointF()

    private fun pickHandle(
        settings: AppSettings,
        tf: FrameTransform,
        x: Float,
        y: Float
    ): Pair<Int, DragKind> {
        val r = dp(30f)
        // Anchors first: they sit on the zone border and must stay reachable.
        for (i in settings.nodes.indices) {
            val node = settings.nodes[i]
            val rect = rectFor(tf, node.nozzle)
            val ax = tf.toViewNorm(node.jetAnchorX, 0f).x
            if (abs(x - ax) <= r && y >= rect.top - r && y <= rect.bottom + r) {
                dragTouchNorm.set(tf.toImageNorm(x, y).x, tf.toImageNorm(x, y).y)
                return Pair(i, DragKind.ANCHOR)
            }
        }
        for (i in settings.nodes.indices) {
            val rect = rectFor(tf, settings.nodes[i].nozzle)
            val corners = cornerPoints(rect)
            val kinds = listOf(
                DragKind.CORNER_TL, DragKind.CORNER_TR,
                DragKind.CORNER_BL, DragKind.CORNER_BR
            )
            for (c in corners.indices) {
                val p = corners[c]
                if (hypot(x - p.x, y - p.y) <= r) {
                    dragTouchNorm.set(tf.toImageNorm(x, y).x, tf.toImageNorm(x, y).y)
                    return Pair(i, kinds[c])
                }
            }
        }
        for (i in settings.nodes.indices) {
            val rect = rectFor(tf, settings.nodes[i].nozzle)
            if (rect.contains(x, y)) {
                val norm = tf.toImageNorm(x, y)
                dragTouchNorm.set(norm.x, norm.y)
                return Pair(i, DragKind.MOVE)
            }
        }
        return Pair(-1, DragKind.NONE)
    }
}
