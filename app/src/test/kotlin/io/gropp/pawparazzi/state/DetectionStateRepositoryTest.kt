package io.gropp.pawparazzi.state

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DetectionStateRepositoryTest {
    private val repository = DetectionStateRepository()

    @Test
    fun activatingWarningAddsIt() {
        repository.setWarning(Warning.LOW_STORAGE, true)

        assertThat(repository.state.value.warnings).containsExactly(Warning.LOW_STORAGE)
    }

    @Test
    fun activatingWarningTwiceKeepsSameStateInstance() {
        repository.setWarning(Warning.LOW_STORAGE, true)
        val before = repository.state.value

        repository.setWarning(Warning.LOW_STORAGE, true)

        assertThat(repository.state.value).isSameInstanceAs(before)
    }

    @Test
    fun deactivatingWarningRemovesOnlyThatWarning() {
        repository.setWarning(Warning.LOW_STORAGE, true)
        repository.setWarning(Warning.CAMERA_ERROR, true)

        repository.setWarning(Warning.LOW_STORAGE, false)

        assertThat(repository.state.value.warnings).containsExactly(Warning.CAMERA_ERROR)
    }

    @Test
    fun deactivatingAbsentWarningKeepsSameStateInstance() {
        val before = repository.state.value

        repository.setWarning(Warning.THERMAL_PAUSED, false)

        assertThat(repository.state.value).isSameInstanceAs(before)
    }

    @Test
    fun resetRestoresDefaults() {
        repository.update { it.copy(running = true, rebindCount = 3, effectiveFps = 12f) }
        repository.setWarning(Warning.SOUND_FALLBACK, true)

        repository.reset()

        assertThat(repository.state.value).isEqualTo(DetectionUiState())
    }
}
