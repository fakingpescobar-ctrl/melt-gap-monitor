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

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                showPermissionRationale()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        settings = AppSettings.get(this)
        engine = VisionEngine { settings }
        engine.onAnalyzed = { binding.overlay.postInvalidateOnAnimation() }

        binding.overlay.engine = engine
        binding.overlay.settings = settings
        binding.overlay.onZonesChanged = { settings.save() }
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
        val basePadding = dp(6f)
        ViewCompat.setOnApplyWindowInsetsListener(binding.panel) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
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
        }

        binding.btnAutoCal.setOnClickListener {
            engine.requestAutoCalibration()
            toast("Auto-calibration requested")
        }

        binding.btnReset.setOnClickListener {
            settings.resetZones()
            engine.resetSmoothing()
            binding.overlay.invalidate()
            toast("Zones reset to default")
        }

        binding.btnScale.setOnClickListener { askReferenceLength() }

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

    private fun onScalePicked(distancePx: Float) {
        val frameWidth = engine.snapshot?.frameW ?: 0
        if (distancePx <= 1f || frameWidth <= 0) {
            toast("Too short — try again")
            binding.overlay.beginScalePicking()
            return
        }
        settings.mmPerImageWidth = pendingReferenceMm * frameWidth / distancePx
        settings.calibrationSamples += 1
        settings.save()
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
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )

                binding.overlay.cameraActive = true
                binding.previewView.post {
                    runCatching {
                        analysis.targetRotation = binding.previewView.display.rotation
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Camera start failed", t)
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
            Log.w(TAG, "Frame analysis failed", t)
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
        cameraProvider?.unbindAll()
        analysisExecutor.shutdown()
    }

    companion object {
        private const val TAG = "MeltGap"
    }
}
