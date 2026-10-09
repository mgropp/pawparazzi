package io.gropp.pawparazzi.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import io.gropp.pawparazzi.core.detection.FpsRange
import io.gropp.pawparazzi.core.detection.FpsRangeSelector
import java.util.concurrent.ExecutorService
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

data class BoundSummary(
    val analysisSize: Size?,
    val captureSize: Size?,
    val previewSize: Size?,
    val fpsRange: FpsRange?,
)

@OptIn(ExperimentalCamera2Interop::class)
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val analysisExecutor: ExecutorService,
    private val analyzer: ImageAnalysis.Analyzer,
    private val onCameraError: (CameraState.StateError?) -> Unit,
) {
    private var provider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var preview: Preview? = null
    private var camera: Camera? = null
    private var surfaceProvider: Preview.SurfaceProvider? = null
    private var rotation: Int = android.view.Surface.ROTATION_90
    private var fps: Int = 5
    private var appliedRange: FpsRange? = null
    private var bound = false
    private var observedState: androidx.lifecycle.LiveData<CameraState>? = null
    private val stateObserver = androidx.lifecycle.Observer<CameraState> { onCameraError(it.error) }

    var imageCapture: ImageCapture? = null
        private set

    var lastSummary: BoundSummary? = null
        private set

    suspend fun bind(fps: Int, rotation: Int, surfaceProvider: Preview.SurfaceProvider?): BoundSummary {
        this.fps = fps
        this.rotation = rotation
        this.surfaceProvider = surfaceProvider
        return rebind()
    }

    suspend fun rebind(): BoundSummary {
        val cameraProvider = provider ?: awaitProvider().also { provider = it }
        cameraProvider.unbindAll()
        val range = selectFpsRange(cameraProvider)
        appliedRange = range

        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setTargetRotation(rotation)
            .also { applyFpsRange(Camera2Interop.Extender(it), range) }
            .build()
            .also { it.setAnalyzer(analysisExecutor, analyzer) }

        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(resolutionSelector(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY))
            .setTargetRotation(rotation)
            .also { applyFpsRange(Camera2Interop.Extender(it), range) }
            .build()

        val previewUseCase = surfaceProvider?.let { sp ->
            Preview.Builder()
                .setResolutionSelector(resolutionSelector(null))
                .setTargetRotation(rotation)
                .also { applyFpsRange(Camera2Interop.Extender(it), range) }
                .build()
                .also { it.setSurfaceProvider(sp) }
        }

        val useCases = listOfNotNull<UseCase>(previewUseCase, analysis, capture)
        val boundCamera = cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, *useCases.toTypedArray())
        camera = boundCamera
        imageAnalysis = analysis
        imageCapture = capture
        preview = previewUseCase
        bound = true

        ContextCompat.getMainExecutor(context).execute {
            observedState?.removeObserver(stateObserver)
            observedState = boundCamera.cameraInfo.cameraState.also { it.observe(lifecycleOwner, stateObserver) }
        }

        val summary = BoundSummary(
            analysisSize = analysis.resolutionInfo?.resolution,
            captureSize = capture.resolutionInfo?.resolution,
            previewSize = previewUseCase?.resolutionInfo?.resolution,
            fpsRange = range,
        )
        lastSummary = summary
        val level = runCatching {
            Camera2CameraInfo.from(boundCamera.cameraInfo)
                .getCameraCharacteristic(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
        }.getOrNull()
        Log.i(TAG, "Bound: hardwareLevel=$level $summary")
        return summary
    }

    suspend fun onFpsChanged(newFps: Int) {
        fps = newFps
        if (!bound) return
        val cameraProvider = provider ?: return
        if (selectFpsRange(cameraProvider) != appliedRange) {
            Log.i(TAG, "FPS range changed, rebinding")
            rebind()
        }
    }

    suspend fun setSurfaceProvider(newProvider: Preview.SurfaceProvider?) {
        val hadPreview = surfaceProvider != null
        surfaceProvider = newProvider
        if (!bound) return
        if (hadPreview != (newProvider != null)) rebind() else preview?.setSurfaceProvider(newProvider)
    }

    fun setTargetRotation(newRotation: Int) {
        rotation = newRotation
        imageAnalysis?.targetRotation = newRotation
        imageCapture?.targetRotation = newRotation
        preview?.targetRotation = newRotation
    }

    fun unbind() {
        provider?.unbindAll()
        bound = false
        camera = null
        imageAnalysis = null
        imageCapture = null
        preview = null
    }

    private fun selectFpsRange(cameraProvider: ProcessCameraProvider): FpsRange? {
        val info = CameraSelector.DEFAULT_BACK_CAMERA.filter(cameraProvider.availableCameraInfos).firstOrNull() ?: return null
        val ranges = Camera2CameraInfo.from(info)
            .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.map { FpsRange(it.lower, it.upper) }
            .orEmpty()
        return FpsRangeSelector.select(ranges, fps)
    }

    private fun applyFpsRange(extender: Camera2Interop.Extender<*>, range: FpsRange?) {
        if (range == null) return
        extender.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(range.lower, range.upper))
    }

    private fun resolutionSelector(strategy: ResolutionStrategy?): ResolutionSelector =
        ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .also { if (strategy != null) it.setResolutionStrategy(strategy) }
            .build()

    private suspend fun awaitProvider(): ProcessCameraProvider = suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                try {
                    cont.resume(future.get())
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    private companion object {
        const val TAG = "CameraController"
    }
}
