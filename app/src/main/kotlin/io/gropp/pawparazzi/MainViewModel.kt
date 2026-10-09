package io.gropp.pawparazzi

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.gropp.pawparazzi.actions.SoundPlayer
import io.gropp.pawparazzi.state.DetectionStateRepository
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    stateRepository: DetectionStateRepository,
    private val soundPlayer: SoundPlayer,
) : ViewModel() {
    val state = stateRepository.state
    val mediaMuted = soundPlayer.mediaMuted

    fun refreshMuted() = soundPlayer.refreshMuted()
}
