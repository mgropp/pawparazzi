package io.gropp.pawparazzi.service

import androidx.camera.core.Preview
import com.google.common.truth.Truth.assertThat
import io.gropp.pawparazzi.camera.CaptureEvent
import io.gropp.pawparazzi.core.detection.FrameWatchdog
import io.gropp.pawparazzi.core.detection.Label
import io.gropp.pawparazzi.core.detection.LandscapeRotationFilter
import io.gropp.pawparazzi.core.detection.ThermalMode
import io.gropp.pawparazzi.core.detection.ThermalPolicy
import io.gropp.pawparazzi.core.settings.AppSettings
import io.gropp.pawparazzi.state.DetectionStateRepository
import io.gropp.pawparazzi.state.Warning
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DetectionCoordinatorTest {
    private val clock = FakeClock()
    private val camera = FakeCamera()
    private val state = DetectionStateRepository()
    private val settingsFlow = MutableStateFlow(AppSettings(fps = 5))
    private val surfaceFlow = MutableStateFlow<Preview.SurfaceProvider?>(null)
    private val soundSources = mutableListOf<Pair<Label, String?>>()
    private val detectors = mutableListOf<FakeDetector>()
    private val notifications = mutableListOf<Unit>()
    private val currentSettings = CurrentSettings()
    private val pipeline = DetectionPipeline(currentSettings, state, { 0L }, {})
    private val watchdog = FrameWatchdog(clock)

    private fun TestScope.coordinator(initialRotation: Int = LandscapeRotationFilter.ROTATION_90) =
        DetectionCoordinator(
            scope = backgroundScope,
            currentSettings = currentSettings,
            settingsFlow = settingsFlow,
            surfaceProvider = surfaceFlow,
            soundFallbackLabels = emptyFlow(),
            camera = camera,
            setSoundSource = { label, uri -> soundSources += label to uri },
            detectorFactory = { FakeDetector().also { detectors += it } },
            pipeline = pipeline,
            watchdog = watchdog,
            rotationFilter = LandscapeRotationFilter(initialRotation),
            stateRepository = state,
            refreshNotification = { notifications += Unit },
            thermalPolicy = ThermalPolicy(),
        )

    private fun TestScope.startedCoordinator() =
        coordinator().also {
            it.start()
            runCurrent()
        }

    private fun TestScope.tickSeconds(seconds: Int) =
        repeat(seconds) {
            clock.advance(1000)
            advanceTimeBy(1000)
            runCurrent()
        }

    private val warnings
        get() = state.state.value.warnings

    @Test
    fun startBindsCameraWithSettingsAndPublishesCaptureResolution() = runTest {
        startedCoordinator()

        assertThat(camera.calls).containsExactly("bind(fps=5,rotation=1)")
        assertThat(state.state.value.captureResolution).isEqualTo("4000x3000")
        assertThat(detectors).hasSize(1)
    }

    @Test
    fun startAppliesSoundSources() = runTest {
        settingsFlow.value = AppSettings(catSoundUri = "cat", dogSoundUri = "dog")

        startedCoordinator()

        assertThat(soundSources).containsExactly(Label.CAT to "cat", Label.DOG to "dog")
    }

    @Test
    fun cameraBindFailureSetsCameraError() = runTest {
        camera.bindFailure = IllegalStateException("no camera")

        startedCoordinator()

        assertThat(warnings).contains(Warning.CAMERA_ERROR)
    }

    @Test
    fun thermalPauseTearsEverythingDown() = runTest {
        val coordinator = startedCoordinator()
        val detector = coordinator.detector!!
        state.update {
            it.copy(
                tracks =
                    listOf(io.gropp.pawparazzi.state.TrackUi(1, Label.CAT, 0.9f, box(0.5f, 0.5f)))
            )
        }

        coordinator.onThermalStatus(CRITICAL)
        runCurrent()

        assertThat(camera.count("unbind")).isEqualTo(1)
        assertThat(detector.closed).isTrue()
        assertThat(coordinator.detector).isNull()
        assertThat(state.state.value.tracks).isEmpty()
        assertThat(warnings).contains(Warning.THERMAL_PAUSED)
        assertThat(state.state.value.thermalMode).isEqualTo(ThermalMode.PAUSED)
    }

    @Test
    fun watchdogDoesNotFireWhilePaused() = runTest {
        val coordinator = startedCoordinator()
        coordinator.onThermalStatus(CRITICAL)
        runCurrent()

        tickSeconds(200)

        assertThat(camera.count("rebind")).isEqualTo(0)
        assertThat(state.state.value.rebindCount).isEqualTo(0)
    }

    @Test
    fun resumingFromPauseRecreatesDetectorRebindsCameraAndResumesWatchdog() = runTest {
        val coordinator = startedCoordinator()
        coordinator.onThermalStatus(CRITICAL)
        runCurrent()
        camera.calls.clear()

        coordinator.onThermalStatus(NONE)
        runCurrent()

        assertThat(camera.calls).containsExactly("bind(fps=5,rotation=1)")
        assertThat(detectors).hasSize(2)
        assertThat(coordinator.detector).isSameInstanceAs(detectors.last())
        assertThat(warnings).containsNoneOf(Warning.THERMAL_PAUSED, Warning.THERMAL_THROTTLED)

        tickSeconds(11)
        assertThat(camera.count("rebind")).isEqualTo(1)
    }

    @Test
    fun pausedToThrottledAlsoResumes() = runTest {
        val coordinator = startedCoordinator()
        coordinator.onThermalStatus(CRITICAL)
        runCurrent()

        coordinator.onThermalStatus(MODERATE)
        runCurrent()

        assertThat(detectors).hasSize(2)
        assertThat(camera.count("bind(fps=5,rotation=1)")).isEqualTo(2)
        assertThat(warnings).contains(Warning.THERMAL_THROTTLED)
        assertThat(warnings).doesNotContain(Warning.THERMAL_PAUSED)
    }

    @Test
    fun throttlingTogglesWarningCapsFpsAndDoesNotRebind() = runTest {
        val coordinator = startedCoordinator()
        camera.calls.clear()

        coordinator.onThermalStatus(SEVERE)
        runCurrent()

        assertThat(warnings).contains(Warning.THERMAL_THROTTLED)
        assertThat(coordinator.effectiveFps()).isEqualTo(1)
        assertThat(camera.calls).isEmpty()

        coordinator.onThermalStatus(NONE)
        runCurrent()

        assertThat(warnings).doesNotContain(Warning.THERMAL_THROTTLED)
        assertThat(coordinator.effectiveFps()).isEqualTo(5)
        assertThat(camera.calls).isEmpty()
    }

    @Test
    fun unchangedThermalModeChangesNothing() = runTest {
        val coordinator = startedCoordinator()
        val before = state.state.value
        val notificationsBefore = notifications.size

        coordinator.onThermalStatus(MODERATE)
        runCurrent()

        assertThat(state.state.value).isEqualTo(before)
        assertThat(notifications).hasSize(notificationsBefore)
        assertThat(camera.calls).hasSize(1)
    }

    @Test
    fun thermalChangeRefreshesNotification() = runTest {
        val coordinator = startedCoordinator()

        coordinator.onThermalStatus(SEVERE)

        assertThat(notifications).hasSize(1)
    }

    @Test
    fun thermalStatusIsIgnoredBeforeStart() = runTest {
        coordinator().onThermalStatus(CRITICAL)
        runCurrent()

        assertThat(state.state.value.thermalMode).isEqualTo(ThermalMode.NORMAL)
        assertThat(camera.calls).isEmpty()
    }

    @Test
    fun watchdogFiringRebindsCameraAndCountsFailures() = runTest {
        startedCoordinator()

        tickSeconds(11)

        assertThat(camera.count("rebind")).isEqualTo(1)
        assertThat(state.state.value.rebindCount).isEqualTo(1)
        assertThat(warnings).doesNotContain(Warning.REPEATED_REBINDS)
    }

    @Test
    fun repeatedRebindsWarningIsSetAtThirdConsecutiveFailure() = runTest {
        startedCoordinator()

        tickSeconds(10)
        tickSeconds(20)
        assertThat(state.state.value.rebindCount).isEqualTo(2)
        assertThat(warnings).doesNotContain(Warning.REPEATED_REBINDS)

        tickSeconds(40)

        assertThat(state.state.value.rebindCount).isEqualTo(3)
        assertThat(warnings).contains(Warning.REPEATED_REBINDS)
        assertThat(camera.count("rebind")).isEqualTo(3)
    }

    @Test
    fun watchdogRebindFailureIsSwallowedAndLoopContinues() = runTest {
        camera.rebindFailure = IllegalStateException("rebind failed")
        startedCoordinator()

        tickSeconds(10)
        tickSeconds(20)

        assertThat(camera.count("rebind")).isEqualTo(2)
    }

    @Test
    fun firstFrameAfterFailuresClearsCameraErrorAndRepeatedRebinds() = runTest {
        val coordinator = startedCoordinator()
        tickSeconds(70)
        coordinator.onCameraError(true)
        assertThat(warnings).containsAtLeast(Warning.REPEATED_REBINDS, Warning.CAMERA_ERROR)

        coordinator.onFrameArrived()

        assertThat(warnings).containsNoneOf(Warning.REPEATED_REBINDS, Warning.CAMERA_ERROR)
    }

    @Test
    fun cameraErrorWarningFollowsErrorState() = runTest {
        val coordinator = startedCoordinator()

        coordinator.onCameraError(true)
        assertThat(warnings).contains(Warning.CAMERA_ERROR)

        coordinator.onCameraError(false)
        assertThat(warnings).doesNotContain(Warning.CAMERA_ERROR)
    }

    @Test
    fun soundsAreReappliedOnlyWhenASoundUriChanges() = runTest {
        startedCoordinator()
        soundSources.clear()

        settingsFlow.value = settingsFlow.value.copy(cooldownSec = 99)
        runCurrent()
        assertThat(soundSources).isEmpty()

        settingsFlow.value = settingsFlow.value.copy(dogSoundUri = "dog2")
        runCurrent()
        assertThat(soundSources).containsExactly(Label.CAT to null, Label.DOG to "dog2")
    }

    @Test
    fun fpsChangeIsForwardedToCameraOnlyWhenFpsChanged() = runTest {
        startedCoordinator()
        camera.calls.clear()

        settingsFlow.value = settingsFlow.value.copy(cooldownSec = 99)
        runCurrent()
        assertThat(camera.calls).isEmpty()

        settingsFlow.value = settingsFlow.value.copy(fps = 10)
        runCurrent()
        assertThat(camera.calls).containsExactly("onFpsChanged(10)")
        assertThat(currentSettings.value.fps).isEqualTo(10)
    }

    @Test
    fun fpsChangeIsNotForwardedWhilePaused() = runTest {
        val coordinator = startedCoordinator()
        coordinator.onThermalStatus(CRITICAL)
        runCurrent()
        camera.calls.clear()

        settingsFlow.value = settingsFlow.value.copy(fps = 10)
        runCurrent()

        assertThat(camera.calls).isEmpty()
        assertThat(currentSettings.value.fps).isEqualTo(10)
    }

    @Test
    fun surfaceChangesAreForwardedToCamera() = runTest {
        startedCoordinator()
        camera.calls.clear()

        surfaceFlow.value = Preview.SurfaceProvider {}
        runCurrent()

        assertThat(camera.calls).containsExactly("setSurfaceProvider(true)")
    }

    @Test
    fun acceptedLandscapeRotationSetsTargetRotationAndResetsPipeline() = runTest {
        val coordinator = startedCoordinator()
        state.update {
            it.copy(
                tracks =
                    listOf(io.gropp.pawparazzi.state.TrackUi(1, Label.CAT, 0.9f, box(0.5f, 0.5f)))
            )
        }
        camera.calls.clear()

        coordinator.onRotation(LandscapeRotationFilter.ROTATION_270)

        assertThat(camera.calls).containsExactly("setTargetRotation(3)")
        assertThat(state.state.value.tracks).isEmpty()
    }

    @Test
    fun portraitAndUnchangedRotationsAreIgnored() = runTest {
        val coordinator = startedCoordinator()
        state.update {
            it.copy(
                tracks =
                    listOf(io.gropp.pawparazzi.state.TrackUi(1, Label.CAT, 0.9f, box(0.5f, 0.5f)))
            )
        }
        camera.calls.clear()

        coordinator.onRotation(0)
        coordinator.onRotation(2)
        coordinator.onRotation(LandscapeRotationFilter.ROTATION_90)

        assertThat(camera.calls).isEmpty()
        assertThat(state.state.value.tracks).hasSize(1)
    }

    @Test
    fun lowStorageEventSetsWarningAndRefreshesNotification() = runTest {
        val coordinator = startedCoordinator()

        coordinator.onCaptureEvent(CaptureEvent.LowStorage(10L))

        assertThat(warnings).contains(Warning.LOW_STORAGE)
        assertThat(notifications).hasSize(1)
    }

    @Test
    fun storageOkEventClearsWarning() = runTest {
        val coordinator = startedCoordinator()
        coordinator.onCaptureEvent(CaptureEvent.LowStorage(10L))

        coordinator.onCaptureEvent(CaptureEvent.StorageOk)

        assertThat(warnings).doesNotContain(Warning.LOW_STORAGE)
    }

    @Test
    fun releaseUnbindsCameraAndClosesDetector() = runTest {
        val coordinator = startedCoordinator()
        val detector = coordinator.detector!!
        camera.calls.clear()

        coordinator.cancel()
        coordinator.release()

        assertThat(camera.calls).containsExactly("unbind")
        assertThat(detector.closed).isTrue()
        assertThat(coordinator.detector).isNull()
    }

    @Test
    fun cancelStopsWatchdogLoop() = runTest {
        val coordinator = startedCoordinator()
        coordinator.cancel()

        tickSeconds(100)

        assertThat(camera.count("rebind")).isEqualTo(0)
    }

    private companion object {
        const val NONE = 0
        const val MODERATE = 2
        const val SEVERE = 3
        const val CRITICAL = 4
    }
}
