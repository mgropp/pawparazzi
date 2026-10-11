package io.gropp.pawparazzi.service

import com.google.common.truth.Truth.assertThat
import io.gropp.pawparazzi.state.DetectionUiState
import io.gropp.pawparazzi.state.Warning
import org.junit.Test

class NotificationTextTest {
    private val formatTime: (Long) -> String = { "t$it" }

    private fun text(state: DetectionUiState) = notificationText(state, formatTime)

    @Test
    fun defaultTextWhenNothingIsWrong() {
        assertThat(text(DetectionUiState())).isEqualTo("Watching for cats and dogs")
    }

    @Test
    fun thermalPauseWinsOverRepeatedRebinds() {
        val state =
            DetectionUiState(warnings = setOf(Warning.REPEATED_REBINDS, Warning.THERMAL_PAUSED))

        assertThat(text(state)).isEqualTo("Paused: phone too hot")
    }

    @Test
    fun repeatedRebindsWinOverDefault() {
        val state = DetectionUiState(warnings = setOf(Warning.REPEATED_REBINDS))

        assertThat(text(state)).isEqualTo("Camera not delivering frames, retrying")
    }

    @Test
    fun otherWarningsDoNotChangeHeadline() {
        val state =
            DetectionUiState(
                warnings =
                    setOf(Warning.CAMERA_ERROR, Warning.THERMAL_THROTTLED, Warning.SOUND_FALLBACK)
            )

        assertThat(text(state)).isEqualTo("Watching for cats and dogs")
    }

    @Test
    fun appendsLastTriggerTime() {
        val state = DetectionUiState(lastTriggerWallMs = 42L)

        assertThat(text(state)).isEqualTo("Watching for cats and dogs · last trigger t42")
    }

    @Test
    fun appendsLowStorageNote() {
        val state = DetectionUiState(warnings = setOf(Warning.LOW_STORAGE))

        assertThat(text(state))
            .isEqualTo("Watching for cats and dogs · low storage, photos skipped")
    }

    @Test
    fun joinsAllPartsInOrder() {
        val state =
            DetectionUiState(
                warnings = setOf(Warning.THERMAL_PAUSED, Warning.LOW_STORAGE),
                lastTriggerWallMs = 7L,
            )

        assertThat(text(state))
            .isEqualTo("Paused: phone too hot · last trigger t7 · low storage, photos skipped")
    }
}
