package io.gropp.pawparazzi.core.detection

object DetectionFilter {
    fun apply(detections: List<Detection>, scoreThreshold: Float, maxResults: Int): List<Detection> =
        detections
            .filter { it.score >= scoreThreshold }
            .sortedByDescending { it.score }
            .take(maxResults)
}
