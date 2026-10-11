package io.gropp.pawparazzi.camera

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

data class RawDetection(
    val categoryName: String?,
    val score: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

class MonotonicTimestamps(private val clock: Clock) {
    private var last = Long.MIN_VALUE

    @Synchronized
    fun next(): Long {
        val timestampMs = maxOf(clock.nowMs(), last + 1)
        last = timestampMs
        return timestampMs
    }
}

class DetectionResultMapper {
    private val sentSizes = ConcurrentHashMap<Long, Pair<Int, Int>>()

    fun recordSent(timestampMs: Long, width: Int, height: Int) {
        sentSizes[timestampMs] = width to height
    }

    fun discardSent(timestampMs: Long) {
        sentSizes.remove(timestampMs)
    }

    fun clear() {
        sentSizes.clear()
    }

    fun map(timestampMs: Long, raw: List<RawDetection>): FrameDetections? {
        val (width, height) = sentSizes.remove(timestampMs) ?: return null
        val detections = raw.mapNotNull { det ->
            val label = when (det.categoryName) {
                "cat" -> Label.CAT
                "dog" -> Label.DOG
                else -> return@mapNotNull null
            }
            Detection(
                label,
                det.score,
                NormBox(
                    (det.left / width).coerceIn(0f, 1f),
                    (det.top / height).coerceIn(0f, 1f),
                    (det.right / width).coerceIn(0f, 1f),
                    (det.bottom / height).coerceIn(0f, 1f),
                ),
            )
        }
        return FrameDetections(timestampMs, detections, width, height)
    }
}
