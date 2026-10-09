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
import io.gropp.pawparazzi.core.detection.Detection
import io.gropp.pawparazzi.core.detection.Label
import io.gropp.pawparazzi.core.detection.NormBox
import java.util.concurrent.ConcurrentHashMap

data class FrameDetections(
    val timestampMs: Long,
    val detections: List<Detection>,
    val imageWidth: Int,
    val imageHeight: Int,
) {
    val aspect: Float get() = imageWidth.toFloat() / imageHeight.toFloat()
}

class ObjectDetectorWrapper(
    context: Context,
    private val clock: Clock,
    private val onResult: (FrameDetections) -> Unit,
) : AutoCloseable {
    private val sentSizes = ConcurrentHashMap<Long, Pair<Int, Int>>()
    private var lastTimestampMs = Long.MIN_VALUE
    private val detector: ObjectDetector

    init {
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath(MODEL_ASSET)
                    .setDelegate(Delegate.CPU)
                    .build(),
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
        val timestampMs = maxOf(clock.nowMs(), lastTimestampMs + 1)
        lastTimestampMs = timestampMs
        sentSizes[timestampMs] = upright.width to upright.height
        val image = BitmapImageBuilder(upright).build()
        try {
            detector.detectAsync(image, timestampMs)
        } catch (e: Exception) {
            sentSizes.remove(timestampMs)
            throw e
        } finally {
            image.close()
        }
    }

    override fun close() {
        detector.close()
        sentSizes.clear()
    }

    private fun handleResult(result: ObjectDetectorResult) {
        try {
            val timestampMs = result.timestampMs()
            val (width, height) = sentSizes.remove(timestampMs) ?: return
            val detections = result.detections().mapNotNull { det ->
                val category = det.categories().firstOrNull() ?: return@mapNotNull null
                val label = when (category.categoryName()) {
                    "cat" -> Label.CAT
                    "dog" -> Label.DOG
                    else -> return@mapNotNull null
                }
                val b = det.boundingBox()
                Detection(
                    label,
                    category.score(),
                    NormBox(
                        (b.left / width).coerceIn(0f, 1f),
                        (b.top / height).coerceIn(0f, 1f),
                        (b.right / width).coerceIn(0f, 1f),
                        (b.bottom / height).coerceIn(0f, 1f),
                    ),
                )
            }
            Log.d(TAG, "raw detections @$timestampMs: $detections")
            onResult(FrameDetections(timestampMs, detections, width, height))
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
