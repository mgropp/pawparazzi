package io.gropp.pawparazzi.camera

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult
import io.gropp.pawparazzi.core.detection.Clock

class ObjectDetectorWrapper(
    context: Context,
    private val clock: Clock,
    private val onResult: (FrameDetections) -> Unit,
) : AutoCloseable {
    private val resultMapper = DetectionResultMapper()
    private val timestamps = MonotonicTimestamps(clock)
    private val detector: ObjectDetector

    init {
        val options =
            ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath(MODEL_ASSET)
                        .setDelegate(Delegate.CPU)
                        .build()
                )
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setScoreThreshold(SCORE_THRESHOLD)
                .setMaxResults(MAX_RESULTS)
                .setCategoryAllowlist(listOf("cat", "dog"))
                .setResultListener { result, _ -> handleResult(result) }
                .setErrorListener { Log.e(TAG, "Detector error", it) }
                .build()
        detector = ObjectDetector.createFromOptions(context, options)
    }

    @Synchronized
    fun detect(upright: Bitmap) {
        val timestampMs = timestamps.next()
        resultMapper.recordSent(timestampMs, upright.width, upright.height)
        val image = BitmapImageBuilder(upright).build()
        try {
            detector.detectAsync(image, timestampMs)
        } catch (e: Exception) {
            resultMapper.discardSent(timestampMs)
            throw e
        } finally {
            image.close()
        }
    }

    override fun close() {
        detector.close()
        resultMapper.clear()
    }

    private fun handleResult(result: ObjectDetectorResult) {
        try {
            val timestampMs = result.timestampMs()
            val raw =
                result.detections().map { det ->
                    val box = det.boundingBox()
                    RawDetection(
                        det.categories().firstOrNull()?.categoryName(),
                        det.categories().firstOrNull()?.score() ?: 0f,
                        box.left,
                        box.top,
                        box.right,
                        box.bottom,
                    )
                }
            val frame = resultMapper.map(timestampMs, raw) ?: return
            Log.d(TAG, "raw detections @$timestampMs: ${frame.detections}")
            onResult(frame)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle detection result", e)
        }
    }

    private companion object {
        const val TAG = "ObjectDetector"
        const val MODEL_ASSET = "efficientdet_lite0.tflite"
        const val SCORE_THRESHOLD = 0.10f
        const val MAX_RESULTS = 20
    }
}
