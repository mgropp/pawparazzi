package io.gropp.pawparazzi.state

import io.gropp.pawparazzi.core.detection.Label
import io.gropp.pawparazzi.core.detection.NormBox
import io.gropp.pawparazzi.core.detection.ThermalMode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class Warning {
    CAMERA_ERROR,
    REPEATED_REBINDS,
    THERMAL_THROTTLED,
    THERMAL_PAUSED,
    LOW_STORAGE,
    SOUND_FALLBACK,
}

data class TrackUi(val id: Long, val label: Label, val score: Float, val box: NormBox)

data class DetectionUiState(
    val running: Boolean = false,
    val tracks: List<TrackUi> = emptyList(),
    val imageAspect: Float = 4f / 3f,
    val effectiveFps: Float = 0f,
    val lastTriggerWallMs: Long? = null,
    val warnings: Set<Warning> = emptySet(),
    val captureResolution: String? = null,
    val rebindCount: Int = 0,
    val thermalMode: ThermalMode = ThermalMode.NORMAL,
)

@Singleton
class DetectionStateRepository @Inject constructor() {
    private val _state = MutableStateFlow(DetectionUiState())
    val state: StateFlow<DetectionUiState> = _state.asStateFlow()

    fun update(transform: (DetectionUiState) -> DetectionUiState) = _state.update(transform)

    fun setWarning(warning: Warning, active: Boolean) = _state.update {
        if (active == (warning in it.warnings)) it else it.copy(warnings = if (active) it.warnings + warning else it.warnings - warning)
    }

    fun reset() {
        _state.value = DetectionUiState()
    }
}
