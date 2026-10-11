package io.gropp.pawparazzi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.gropp.pawparazzi.actions.SoundCheck
import io.gropp.pawparazzi.actions.SoundPlayer
import io.gropp.pawparazzi.core.detection.Label
import io.gropp.pawparazzi.core.settings.AppSettings
import io.gropp.pawparazzi.core.settings.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel
@Inject
constructor(
    private val repository: SettingsRepository,
    private val soundChecker: SoundChecker,
) : ViewModel() {
    val settings: StateFlow<AppSettings> =
        repository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { repository.update(transform) }
    }

    fun reset() {
        viewModelScope.launch { repository.reset() }
    }

    fun dismissMessage() {
        _message.value = null
    }

    fun pickSound(label: Label, uri: String) {
        viewModelScope.launch {
            when (val check = soundChecker.check(uri)) {
                SoundCheck.Ok ->
                    repository.update {
                        when (label) {
                            Label.CAT -> it.copy(catSoundUri = uri)
                            Label.DOG -> it.copy(dogSoundUri = uri)
                        }
                    }
                is SoundCheck.TooLong ->
                    _message.value =
                        "Sound is ${check.durationMs / 1000.0} s long; the limit is ${SoundPlayer.MAX_DURATION_MS / 1000} s."
                SoundCheck.Unreadable -> _message.value = "That file could not be read as audio."
            }
        }
    }

    fun clearSound(label: Label) = update {
        when (label) {
            Label.CAT -> it.copy(catSoundUri = null)
            Label.DOG -> it.copy(dogSoundUri = null)
        }
    }
}
