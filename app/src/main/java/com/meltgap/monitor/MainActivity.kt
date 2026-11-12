package com.meltgap.monitor

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.util.TypedValue
import android.view.WindowManager
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Camera
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.meltgap.monitor.databinding.ActivityMainBinding
import com.meltgap.monitor.diag.DiagLog
import com.meltgap.monitor.ui.OverlayView
import com.meltgap.monitor.vision.Vision
import com.meltgap.monitor.vision.VisionEngine
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: AppSettings
    private lateinit var engine: VisionEngine

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile
    private var cameraProvider: ProcessCameraProvider? = null

    private var pendingReferenceMm = 0f
    private var analyzeErrors = 0
    private var lastAnalyzeErrorAt = 0L

    private var camera: Camera? = null
    private var zoomRatio = 1f
    private var minZoom = 1f
    private var maxZoom = 1f

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                DiagLog.i("CAM", "camera permission granted")
                startCamera()
            } else {
                DiagLog.w("CAM", "camera permission denied")
                showPermissionRationale()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        DiagLog.init(applicationContext)
        DiagLog.i("APP", "Melt Gap Monitor ${BuildConfig.VERSION_NAME} started")

        settings = AppSettings.get(this)
        DiagLog.i(
            "APP",
            String.format(
                Locale.US,
                "settings: mmPerWidth=%.2f target=%.1f tol=%.1f autocal=%s nodes=%d",
                settings.mmPerImageWidth, settings.targetGapMm, settings.toleranceMm,
                settings.autoCalOk, settings.nodes.size
            )
        )
        engine = VisionEngine { settings }
        engine.onAnalyzed = { binding.overlay.postInvalidateOnAnimation() }

        binding.overlay.engine = engine
        binding.overlay.settings = settings
        binding.overlay.onZonesChanged = {
            settings.save()
            DiagLog.i(
                "UI",
                "zones saved: " + settings.nodes.joinToString("; ") {
                    String.format(
                        Locale.US,
                        "%s [%.2f..%.2f] axis %.2f",
                        it.name, it.nozzle.left, it.nozzle.right, it.jetAnchorX
                    )
                }
            )
        }
        binding.overlay.onScalePicked = { px -> onScalePicked(px) }

        applyInsets()
        wireButtons()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun applyInsets() {
        val basePadding = dp(6f).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.panel) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            binding.overlay.safeInsets = android.graphics.Rect(
                bars.left, bars.top, bars.right, bars.bottom
            )
            view.setPadding(
                view.paddingLeft + bars.left,
                view.paddingTop,
                view.paddingRight + bars.right,
                basePadding + bars.bottom
            )
            insets
        }
    }

    private fun wireButtons() {
        binding.btnMode.setOnClickListener {
            val next = if (binding.overlay.mode == OverlayView.Mode.RUN) {
                OverlayView.Mode.CALIBRATE
            } else {
                OverlayView.Mode.RUN
            }
            binding.overlay.mode = next
            binding.btnMode.text = if (next == OverlayView.Mode.RUN) {
                getString(R.string.mode_run)
            } else {
                getString(R.string.mode_calibrate)
            }
            DiagLog.i("UI", "mode -> $next")
        }

        binding.btnAutoCal.setOnClickListener {
            engine.requestAutoCalibration()
            DiagLog.i("UI", "auto-calibration requested")
            binding.root.postDelayed({
                val message = engine.lastAutoCalMessage
                if (message.isNotEmpty()) toast(message)
            }, 700)
        }

        binding.btnReset.setOnClickListener {
            settings.resetZones()
            engine.resetSmoothing()
            binding.overlay.invalidate()
            DiagLog.i("UI", "zones reset to defaults")
            toast("Zones reset to default")
        }

        binding.btnScale.setOnClickListener { askReferenceLength() }

        binding.btnZoomIn.setOnClickListener { applyZoom(zoomRatio * 1.5f) }
        binding.btnZoomOut.setOnClickListener { applyZoom(zoomRatio / 1.5f) }
        binding.overlay.onPinchZoom = { factor -> applyZoom(zoomRatio * factor) }

        binding.btnLog.setOnClickListener {
            val next = !binding.overlay.showLog
            binding.overlay.showLog = next
            binding.btnLog.text = if (next) "LOG ON" else getString(R.string.log)
            DiagLog.i("UI", "log panel ${if (next) "shown" else "hidden"}")
        }
        binding.btnLog.setOnLongClickListener {
            DiagLog.clear()
            toast("Log cleared")
            binding.overlay.invalidate()
            true
        }

        binding.seekTolerance.setOnSeekBarChangeListener(object :
            android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val mm = (progress.coerceAtLeast(1) / 10f)
                settings.toleranceMm = mm
                binding.txtTolerance.text = String.format(Locale.US, "±%.1f", mm)
                if (fromUser) settings.save()
            }

            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
        })
    }

    private fun askReferenceLength() {
        val input = EditText(this).apply {
            hint = getString(R.string.scale_dialog_hint)
            setText(if (settings.calibrationSamples > 0) "" else "50")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val pad = (dp(20f)).toInt()
        val container = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.scale_dialog_title)
            .setMessage(R.string.scale_dialog_message)
            .setView(container)
            .setPositiveButton(R.string.ok) { _, _ ->
                val mm = input.text.toString().replace(',', '.').toFloatOrNull()
                if (mm == null || mm <= 0f) {
                    toast("Invalid reference length")
                    return@setPositiveButton
                }
                pendingReferenceMm = mm
                binding.overlay.beginScalePicking()
                toast("Tap the two ends of a ${mm}mm reference")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun applyZoom(target: Float) {
        val cam = camera ?: return
        val clamped = target.coerceIn(minZoom, maxZoom)
        if (kotlin.math.abs(clamped - zoomRatio) < 0.01f) return
        val future = cam.cameraControl.setZoomRatio(clamped)
        future.addListener({
            runCatching { future.get() }
                .onFailure { DiagLog.e("ZOOM", "setZoomRatio failed", it) }
        }, ContextCompat.getMainExecutor(this))
        DiagLog.i(
            "ZOOM",
            String.format(Locale.US, "-> %.2fx (range %.1f..%.1f)", clamped, minZoom, maxZoom)
        )
    }

    private fun onScalePicked(distancePx: Float) {
        val frameWidth = engine.snapshot?.frameW ?: 0
        if (distancePx <= 1f || frameWidth <= 0) {
            toast("Too short — try again")
            binding.overlay.beginScalePicking()
            return
        }
        settings.mmPerImageWidth = pendingReferenceMm * frameWidth / distancePx
        settings.calibrationZoom = zoomRatio
        settings.calibrationSamples += 1
        settings.save()
        DiagLog.i(
            "CAL",
            String.format(
                Locale.US,
                "scale: %.4f mm/px at zoom %.2fx, reference %.1f mm over %.1f px (frame %d px)",
                settings.mmPerPixel(frameWidth), zoomRatio, pendingReferenceMm, distancePx, frameWidth
            )
        )
        binding.overlay.mode = OverlayView.Mode.CALIBRATE
        binding.btnMode.text = getString(R.string.mode_calibrate)
        toast(
            String.format(
                Locale.US,
                "Scale: %.4f mm/px • frame %.1f mm wide",
                settings.mmPerPixel(frameWidth),
                settings.mmPerImageWidth
            )
        )
    }

    private fun startCamera() {
        DiagLog.i("CAM", "starting CameraX (preview 4:3, analysis 640x480 RGBA)")
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider

                val aspect = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                val previewSelector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(aspect)
                    .build()

                val preview = Preview.Builder()
                    .setResolutionSelector(previewSelector)
                    .build()
                preview.setSurfaceProvider(binding.previewView.surfaceProvider)

                val analysisSelector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(aspect)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(640, 480),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()

                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(analysisSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()

                analysis.setAnalyzer(analysisExecutor) { proxy -> analyzeFrame(proxy) }

                provider.unbindAll()
                val boundCamera = provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
                camera = boundCamera

                boundCamera.cameraInfo.zoomState.observe(this) { state ->
                    val previous = engine.zoomRatio
                    zoomRatio = state.zoomRatio
                    minZoom = state.minZoomRatio
                    maxZoom = state.maxZoomRatio
                    engine.zoomRatio = state.zoomRatio
                    if (kotlin.math.abs(previous - state.zoomRatio) > 0.01f) {
                        engine.resetSmoothing()
                    }
                    binding.overlay.postInvalidateOnAnimation()
                }

                binding.overlay.cameraActive = true
                DiagLog.i(
                    "CAM",
                    String.format(
                        Locale.US,
                        "camera bound; zoom range %.1f..%.1fx",
                        boundCamera.cameraInfo.zoomState.value?.minZoomRatio ?: 1f,
                        boundCamera.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f
                    )
                )
                binding.previewView.post {
                    runCatching {
                        analysis.targetRotation = binding.previewView.display.rotation
                        DiagLog.i("CAM", "targetRotation=${analysis.targetRotation}")
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Camera start failed", t)
                DiagLog.e("CAM", "start failed", t)
                binding.overlay.cameraActive = false
                toast("Camera error: ${t.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(proxy: ImageProxy) {
        try {
            val plane = proxy.planes[0]
            val gray = Vision.toGray(
                plane.buffer,
                proxy.width,
                proxy.height,
                plane.rowStride,
                plane.pixelStride
            )
            engine.process(gray, proxy.imageInfo.rotationDegrees)
        } catch (t: Throwable) {
            // Never spam: first error plus one per 5 seconds.
            val now = android.os.SystemClock.elapsedRealtime()
            analyzeErrors++
            if (analyzeErrors == 1 || now - lastAnalyzeErrorAt > 5000L) {
                lastAnalyzeErrorAt = now
                Log.w(TAG, "Frame analysis failed", t)
                DiagLog.e("VISION", "frame analysis failed (#$analyzeErrors)", t)
            }
        } finally {
            proxy.close()
        }
    }

    private fun showPermissionRationale() {
        AlertDialog.Builder(this)
            .setTitle(R.string.camera_permission_title)
            .setMessage(R.string.camera_permission_text)
            .setPositiveButton(R.string.ok) { _, _ ->
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value,
        resources.displayMetrics
    )

    override fun onDestroy() {
        super.onDestroy()
        DiagLog.i("APP", "activity destroyed")
        cameraProvider?.unbindAll()
        analysisExecutor.shutdown()
    }

    companion object {
        private const val TAG = "MeltGap"
    }
}
