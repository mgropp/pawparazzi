package io.gropp.pawparazzi.service

import com.google.common.truth.Truth.assertThat
import io.gropp.pawparazzi.core.detection.Label
import io.gropp.pawparazzi.core.detection.Trigger
import io.gropp.pawparazzi.core.settings.AppSettings
import io.gropp.pawparazzi.state.DetectionStateRepository
import org.junit.Test

class DetectionPipelineTest {
    private val settings = CurrentSettings(AppSettings(minHits = 2, cooldownSec = 30))
    private val state = DetectionStateRepository()
    private val triggers = mutableListOf<List<Trigger>>()
    private var wallClockMs = 1_000_000L
    private val pipeline = DetectionPipeline(settings, state, { wallClockMs }, { triggers += it })

    private fun feed(vararg timestamps: Long) = timestamps.forEach {
        pipeline.onDetections(frame(it, detection()))
    }

    @Test
    fun confirmedTrackTriggersOnceAndIsPublished() {
        feed(0, 200)

        assertThat(triggers).hasSize(1)
        assertThat(triggers.single().map { it.label }).containsExactly(Label.CAT)
        assertThat(state.state.value.tracks).hasSize(1)
        assertThat(state.state.value.tracks.single().label).isEqualTo(Label.CAT)
        assertThat(state.state.value.imageAspect).isWithin(1e-6f).of(640f / 480f)
    }

    @Test
    fun firstSightingDoesNotTriggerYet() {
        feed(0)

        assertThat(triggers).isEmpty()
        assertThat(state.state.value.tracks).hasSize(1)
    }

    @Test
    fun cooldownSuppressesRepeatTriggers() {
        feed(0, 200, 400, 600)

        assertThat(triggers).hasSize(1)
    }

    @Test
    fun triggerRecordsWallClockTime() {
        wallClockMs = 123L

        feed(0, 200)

        assertThat(state.state.value.lastTriggerWallMs).isEqualTo(123L)
    }

    @Test
    fun scoreThresholdIsReadForEveryFrame() {
        pipeline.onDetections(frame(0, detection(score = 0.6f)))
        assertThat(state.state.value.tracks).hasSize(1)

        settings.value = settings.value.copy(scoreThreshold = 0.8f)
        pipeline.onDetections(frame(200, detection(score = 0.6f)))

        assertThat(triggers).isEmpty()
    }

    @Test
    fun maxResultsIsReadForEveryFrame() {
        val detections =
            arrayOf(
                detection(box = box(0.15f, 0.15f)),
                detection(box = box(0.5f, 0.5f)),
                detection(box = box(0.85f, 0.85f)),
            )
        settings.value = settings.value.copy(maxResults = 3)
        pipeline.onDetections(frame(0, *detections))
        assertThat(state.state.value.tracks).hasSize(3)

        pipeline.reset()
        settings.value = settings.value.copy(maxResults = 1)
        pipeline.onDetections(frame(200, *detections))

        assertThat(state.state.value.tracks).hasSize(1)
    }

    @Test
    fun effectiveFpsIsZeroWithOneFrame() {
        feed(0)

        assertThat(state.state.value.effectiveFps).isEqualTo(0f)
    }

    @Test
    fun effectiveFpsIsZeroWhenFramesShareTimestamp() {
        feed(500, 500)

        assertThat(state.state.value.effectiveFps).isEqualTo(0f)
    }

    @Test
    fun effectiveFpsUsesRollingWindowOfTenFrames() {
        feed(*LongArray(5) { it * 1000L })
        feed(*LongArray(10) { 10_000L + it * 100L })

        assertThat(state.state.value.effectiveFps).isWithin(1e-3f).of(10f)
    }

    @Test
    fun effectiveFpsWithTwoFrames() {
        feed(0, 250)

        assertThat(state.state.value.effectiveFps).isWithin(1e-3f).of(4f)
    }

    @Test
    fun resetClearsTracksAndFpsWindow() {
        feed(0, 100, 200)

        pipeline.reset()

        assertThat(state.state.value.tracks).isEmpty()
        feed(10_000)
        assertThat(state.state.value.effectiveFps).isEqualTo(0f)
    }

    @Test
    fun resetClearsCooldownSoNewTracksTriggerAgain() {
        feed(0, 200)
        assertThat(triggers).hasSize(1)

        pipeline.reset()
        feed(400, 600)

        assertThat(triggers).hasSize(2)
    }

    @Test
    fun resultsAfterCloseAreIgnored() {
        feed(0)
        pipeline.close()

        feed(200, 400)

        assertThat(triggers).isEmpty()
        assertThat(state.state.value.effectiveFps).isEqualTo(0f)
        assertThat(state.state.value.tracks).hasSize(1)
    }

    @Test
    fun failingTriggerCallbackDoesNotEscape() {
        val failing = DetectionPipeline(settings, state, { wallClockMs }, { error("boom") })

        failing.onDetections(frame(0, detection()))
        failing.onDetections(frame(200, detection()))

        assertThat(state.state.value.tracks).hasSize(1)
    }
}
