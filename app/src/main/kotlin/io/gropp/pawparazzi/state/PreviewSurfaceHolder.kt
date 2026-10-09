package io.gropp.pawparazzi.state

import androidx.camera.core.Preview
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class PreviewSurfaceHolder @Inject constructor() {
    private val _provider = MutableStateFlow<Preview.SurfaceProvider?>(null)
    val provider: StateFlow<Preview.SurfaceProvider?> = _provider.asStateFlow()

    fun set(provider: Preview.SurfaceProvider?) {
        _provider.value = provider
    }
}
