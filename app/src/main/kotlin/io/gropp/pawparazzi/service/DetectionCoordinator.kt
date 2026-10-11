package io.gropp.pawparazzi.service

import androidx.camera.core.Preview
import io.gropp.pawparazzi.camera.CaptureEvent
import io.gropp.pawparazzi.core.detection.FrameWatchdog
import io.gropp.pawparazzi.core.detection.Label
import io.gropp.pawparazzi.core.detection.LandscapeRotationFilter
import io.gropp.pawparazzi.core.detection.ThermalMode
import io.gropp.pawparazzi.core.detection.ThermalPolicy
import io.gropp.pawparazzi.core.detection.ThermalStatus
import io.gropp.pawparazzi.core.settings.AppSettings
import io.gropp.pawparazzi.state.DetectionStateRepository
import io.gropp.pawparazzi.state.Warning
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DetectionCoordinator<D : AutoCloseable>(
    private val scope: CoroutineScope,
    private val currentSettings: CurrentSettings,
    private val settingsFlow: Flow<AppSettings>,
    private val surfaceProvider: StateFlow<Preview.SurfaceProvider?>,
    private val soundFallbackLabels: Flow<Set<Label>>,
    private val camera: CameraPort,
    private val setSoundSource: (Label, String?) -> Unit,
    private val detectorFactory: () -> D,
    private val pipeline: DetectionPipeline,
    private val watchdog: FrameWatchdog,
    private val rotationFilter: LandscapeRotationFilter,
    private val stateRepository: DetectionStateRepository,
    private val refreshNotification: () -> Unit,
    private val logger: DetectionLogger = SilentDetectionLogger,
    private val thermalPolicy: ThermalPolicy = ThermalPolicy(),
    private val watchdogPollMs: Long = WATCHDOG_POLL_MS,
) {
    private val cameraMutex = Mutex()
    private var supervisorJob: Job? = null

    @Volatile
    var detector: D? = null
        private set

    @Volatile
    var thermalMode: ThermalMode = ThermalMode.NORMAL
        private set

    @Volatile private var started = false

    fun effectiveFps(): Int = ThermalPolicy.effectiveFps(thermalMode, currentSettings.value.fps)

    fun start() {
        started = true
        supervisorJob = scope.launch {
            currentSettings.value = settingsFlow.first()
            applySounds()
            detector = detectorFactory()
            cameraMutex.withLock { bindCamera() }
            launch { settingsFlow.drop(1).collect(::onSettingsChanged) }
            launch { surfaceProvider.drop(1).collect(::onSurfaceChanged) }
            launch {
                soundFallbackLabels.collect {
                    stateRepository.setWarning(Warning.SOUND_FALLBACK, it.isNotEmpty())
                }
            }
            launch { watchdogLoop() }
        }
    }

    fun cancel() {
        started = false
        supervisorJob?.cancel()
    }

    fun release() {
        camera.unbind()
        detector?.close()
        detector = null
    }

    fun onFrameArrived() {
        watchdog.onFrame()
        if (watchdog.consecutiveFailures == 0) {
            stateRepository.setWarning(Warning.CAMERA_ERROR, false)
            stateRepository.setWarning(Warning.REPEATED_REBINDS, false)
        }
    }

    fun onCameraError(hasError: Boolean) {
        stateRepository.setWarning(Warning.CAMERA_ERROR, hasError)
        if (started) refreshNotification()
    }

    fun onRotation(rotation: Int) {
        if (!started) return
        val accepted = rotationFilter.onRotation(rotation) ?: return
        logger.info("Display rotation -> $accepted")
        camera.setTargetRotation(accepted)
        pipeline.reset()
    }

    fun onThermalStatus(status: Int) {
        if (!started) return
        val previous = thermalPolicy.mode
        val mode = thermalPolicy.onStatus(ThermalStatus.fromAndroid(status))
        logger.info("Thermal status $status -> $mode")
        if (mode == previous) return
        thermalMode = mode
        stateRepository.update { it.copy(thermalMode = mode) }
        stateRepository.setWarning(Warning.THERMAL_THROTTLED, mode == ThermalMode.THROTTLED)
        stateRepository.setWarning(Warning.THERMAL_PAUSED, mode == ThermalMode.PAUSED)
        refreshNotification()
        scope.launch {
            cameraMutex.withLock {
                when {
                    mode == ThermalMode.PAUSED -> pauseForHeat()
                    previous == ThermalMode.PAUSED -> resumeFromHeat()
                }
            }
        }
    }

    fun onCaptureEvent(event: CaptureEvent) {
        when (event) {
            is CaptureEvent.LowStorage -> {
                stateRepository.setWarning(Warning.LOW_STORAGE, true)
                refreshNotification()
            }
            CaptureEvent.StorageOk -> stateRepository.setWarning(Warning.LOW_STORAGE, false)
            is CaptureEvent.Saved -> logger.info("Saved ${event.displayName}")
        }
    }

    private suspend fun bindCamera() {
        try {
            val captureResolution =
                camera.bind(
                    currentSettings.value.fps,
                    rotationFilter.current,
                    surfaceProvider.value,
                )
            stateRepository.update { it.copy(captureResolution = captureResolution) }
        } catch (e: Exception) {
            logger.error("Camera bind failed", e)
            stateRepository.setWarning(Warning.CAMERA_ERROR, true)
        }
    }

    private fun onSettingsChanged(new: AppSettings) {
        val old = currentSettings.value
        currentSettings.value = new
        if (old.catSoundUri != new.catSoundUri || old.dogSoundUri != new.dogSoundUri) applySounds()
        if (old.fps != new.fps) {
            scope.launch {
                cameraMutex.withLock {
                    if (thermalMode != ThermalMode.PAUSED) camera.onFpsChanged(new.fps)
                }
            }
        }
    }

    private fun applySounds() {
        val settings = currentSettings.value
        setSoundSource(Label.CAT, settings.catSoundUri)
        setSoundSource(Label.DOG, settings.dogSoundUri)
    }

    private suspend fun onSurfaceChanged(provider: Preview.SurfaceProvider?) {
        cameraMutex.withLock {
            try {
                camera.setSurfaceProvider(provider)
            } catch (e: Exception) {
                logger.error("Preview rebind failed", e)
            }
        }
    }

    private fun pauseForHeat() {
        watchdog.suspend()
        camera.unbind()
        detector?.close()
        detector = null
        pipeline.reset()
    }

    private suspend fun resumeFromHeat() {
        detector = detectorFactory()
        pipeline.reset()
        bindCamera()
        watchdog.resume()
    }

    private suspend fun watchdogLoop() {
        while (true) {
            delay(watchdogPollMs)
            if (!watchdog.poll()) continue
            logger.warn("No frames, rebinding camera (failure #${watchdog.consecutiveFailures})")
            stateRepository.update { it.copy(rebindCount = it.rebindCount + 1) }
            if (watchdog.consecutiveFailures >= REPEATED_FAILURES_WARNING) {
                stateRepository.setWarning(Warning.REPEATED_REBINDS, true)
                refreshNotification()
            }
            cameraMutex.withLock {
                if (thermalMode != ThermalMode.PAUSED) {
                    try {
                        camera.rebind()
                    } catch (e: Exception) {
                        logger.error("Watchdog rebind failed", e)
                    }
                }
            }
        }
    }

    private companion object {
        const val WATCHDOG_POLL_MS = 1000L
        const val REPEATED_FAILURES_WARNING = 3
    }
}
