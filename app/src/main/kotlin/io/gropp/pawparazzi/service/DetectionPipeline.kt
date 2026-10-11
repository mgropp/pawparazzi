package io.gropp.pawparazzi.service

import io.gropp.pawparazzi.camera.FrameDetections
import io.gropp.pawparazzi.core.detection.ActionGate
import io.gropp.pawparazzi.core.detection.DetectionFilter
import io.gropp.pawparazzi.core.detection.GateParams
import io.gropp.pawparazzi.core.detection.Track
import io.gropp.pawparazzi.core.detection.Tracker
import io.gropp.pawparazzi.core.detection.TrackerParams
import io.gropp.pawparazzi.core.detection.Trigger
import io.gropp.pawparazzi.state.DetectionStateRepository
import io.gropp.pawparazzi.state.TrackUi

class DetectionPipeline(
    private val settings: CurrentSettings,
    private val stateRepository: DetectionStateRepository,
    private val wallClockMs: () -> Long,
    private val onTriggers: (List<Trigger>) -> Unit,
    private val logger: DetectionLogger = SilentDetectionLogger,
) {
    private val lock = Any()
    private val tracker = Tracker {
        settings.value.let {
            TrackerParams(
                it.iouThreshold,
                it.centerDistThreshold,
                it.labelDedupeIou,
                it.labelDecay,
                it.gracePeriodMs,
            )
        }
    }
    private val gate = ActionGate { settings.value.let { GateParams(it.cooldownMs, it.minHits) } }
    private val frameTimestamps = ArrayDeque<Long>()

    @Volatile private var closed = false

    fun onDetections(frame: FrameDetections) {
        if (closed) return
        try {
            val s = settings.value
            val filtered = DetectionFilter.apply(frame.detections, s.scoreThreshold, s.maxResults)
            val triggers =
                synchronized(lock) {
                    val update = tracker.update(filtered, frame.aspect, frame.timestampMs)
                    val result = gate.evaluate(update, frame.timestampMs)
                    publishTracks(update.tracks, frame)
                    result
                }
            if (triggers.isNotEmpty()) {
                stateRepository.update { it.copy(lastTriggerWallMs = wallClockMs()) }
                onTriggers(triggers)
            }
        } catch (e: Exception) {
            logger.error("Failed to process detections", e)
        }
    }

    fun reset() {
        synchronized(lock) {
            tracker.reset()
            gate.reset()
            frameTimestamps.clear()
        }
        stateRepository.update { it.copy(tracks = emptyList()) }
    }

    fun close() {
        closed = true
    }

    private fun publishTracks(tracks: List<Track>, frame: FrameDetections) {
        frameTimestamps.addLast(frame.timestampMs)
        while (frameTimestamps.size > FPS_WINDOW) frameTimestamps.removeFirst()
        val spanMs = frameTimestamps.last() - frameTimestamps.first()
        val fps =
            if (frameTimestamps.size > 1 && spanMs > 0) (frameTimestamps.size - 1) * 1000f / spanMs
            else 0f
        stateRepository.update {
            it.copy(
                tracks = tracks.map { t -> TrackUi(t.id, t.label, t.lastScore, t.box) },
                imageAspect = frame.aspect,
                effectiveFps = fps,
            )
        }
    }

    private companion object {
        const val FPS_WINDOW = 10
    }
}
