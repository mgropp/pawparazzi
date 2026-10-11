package io.gropp.pawparazzi.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.view.Display
import android.view.Surface
import androidx.camera.core.CameraState
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import io.gropp.pawparazzi.MainActivity
import io.gropp.pawparazzi.R
import io.gropp.pawparazzi.actions.PhotoStore
import io.gropp.pawparazzi.actions.SoundPlayer
import io.gropp.pawparazzi.camera.CameraController
import io.gropp.pawparazzi.camera.CaptureQueue
import io.gropp.pawparazzi.camera.CaptureRequest
import io.gropp.pawparazzi.camera.FrameAnalyzer
import io.gropp.pawparazzi.camera.ObjectDetectorWrapper
import io.gropp.pawparazzi.core.detection.Clock
import io.gropp.pawparazzi.core.detection.FrameThrottler
import io.gropp.pawparazzi.core.detection.FrameWatchdog
import io.gropp.pawparazzi.core.detection.LandscapeRotationFilter
import io.gropp.pawparazzi.core.detection.Trigger
import io.gropp.pawparazzi.core.settings.SettingsRepository
import io.gropp.pawparazzi.state.DetectionStateRepository
import io.gropp.pawparazzi.state.PreviewSurfaceHolder
import java.util.concurrent.Executors
import javax.inject.Inject

@AndroidEntryPoint
class DetectionService : LifecycleService() {
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var stateRepository: DetectionStateRepository
    @Inject lateinit var surfaceHolder: PreviewSurfaceHolder
    @Inject lateinit var soundPlayer: SoundPlayer
    @Inject lateinit var photoStore: PhotoStore
    @Inject lateinit var clock: Clock

    private val currentSettings = CurrentSettings()
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private lateinit var pipeline: DetectionPipeline
    private lateinit var coordinator: DetectionCoordinator<ObjectDetectorWrapper>
    private lateinit var cameraController: CameraController
    private lateinit var captureQueue: CaptureQueue
    private var wakeLock: PowerManager.WakeLock? = null
    private var running = false
    private val thermalListener = PowerManager.OnThermalStatusChangedListener {
        coordinator.onThermalStatus(it)
    }

    private val displayListener =
        object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit

            override fun onDisplayRemoved(displayId: Int) = Unit

            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) onRotationMaybeChanged()
            }
        }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> stopDetection()
            ACTION_START -> startDetection(intent.getIntExtra(EXTRA_ROTATION, Surface.ROTATION_90))
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseResources()
        super.onDestroy()
    }

    private fun startDetection(initialRotation: Int) {
        if (running) return
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Cannot start foreground", e)
            stopSelf()
            return
        }
        running = true
        isRunning = true
        wakeLock =
            (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pawparazzi:detection")
                .also { it.acquire() }
        soundPlayer.start()
        stateRepository.reset()
        stateRepository.update { it.copy(running = true) }

        pipeline =
            DetectionPipeline(
                settings = currentSettings,
                stateRepository = stateRepository,
                wallClockMs = System::currentTimeMillis,
                onTriggers = ::onTriggers,
                logger = AndroidDetectionLogger,
            )
        val throttler = FrameThrottler { coordinator.effectiveFps() }
        val analyzer =
            FrameAnalyzer(
                clock,
                throttler,
                { coordinator.detector },
                { coordinator.onFrameArrived() },
            )
        cameraController = CameraController(this, this, analysisExecutor, analyzer, ::onCameraError)
        captureQueue =
            CaptureQueue(
                context = this,
                scope = lifecycleScope,
                photoStore = photoStore,
                imageCapture = { cameraController.imageCapture },
                minFreeBytes = { currentSettings.value.minFreeSpaceBytes },
                capBytes = { currentSettings.value.storageCapBytes },
                nowWallMs = System::currentTimeMillis,
                onEvent = { coordinator.onCaptureEvent(it) },
            )
        coordinator =
            DetectionCoordinator(
                scope = lifecycleScope,
                currentSettings = currentSettings,
                settingsFlow = settingsRepository.settings,
                surfaceProvider = surfaceHolder.provider,
                soundFallbackLabels = soundPlayer.fallbackLabels,
                camera = CameraControllerPort(cameraController),
                setSoundSource = soundPlayer::setSource,
                detectorFactory = { ObjectDetectorWrapper(this, clock, pipeline::onDetections) },
                pipeline = pipeline,
                watchdog = FrameWatchdog(clock),
                rotationFilter = LandscapeRotationFilter(initialRotation),
                stateRepository = stateRepository,
                refreshNotification = ::refreshNotification,
                logger = AndroidDetectionLogger,
            )

        val displayManager = getSystemService(DisplayManager::class.java)
        displayManager.registerDisplayListener(displayListener, null)
        (getSystemService(Context.POWER_SERVICE) as PowerManager).addThermalStatusListener(
            ContextCompat.getMainExecutor(this),
            thermalListener,
        )

        coordinator.start()
    }

    private fun onCameraError(error: CameraState.StateError?) {
        if (error != null) Log.w(TAG, "Camera error: code=${error.code}")
        coordinator.onCameraError(error != null)
    }

    private fun onTriggers(triggers: List<Trigger>) {
        Log.i(TAG, "Triggers: $triggers")
        triggers.forEach { soundPlayer.play(it.label) }
        captureQueue.enqueue(CaptureRequest(triggers))
        refreshNotification()
    }

    private fun onRotationMaybeChanged() {
        if (!running) return
        val display =
            getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
                ?: return
        coordinator.onRotation(display.rotation)
    }

    private fun stopDetection() {
        releaseResources()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseResources() {
        if (!running) return
        running = false
        isRunning = false
        pipeline.close()
        coordinator.cancel()
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        (getSystemService(Context.POWER_SERVICE) as PowerManager).removeThermalStatusListener(
            thermalListener
        )
        captureQueue.close()
        coordinator.release()
        soundPlayer.release()
        analysisExecutor.shutdown()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        stateRepository.reset()
    }

    private fun refreshNotification() {
        if (running) notificationManager().notify(NOTIFICATION_ID, buildNotification())
    }

    private fun notificationManager() = getSystemService(NotificationManager::class.java)

    private fun buildNotification(): Notification {
        val manager = notificationManager()
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
        val state = stateRepository.state.value
        val text = notificationText(stateRepository.state.value)
        val openApp =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val stop =
            PendingIntent.getService(
                this,
                1,
                Intent(this, DetectionService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        const val ACTION_START = "io.gropp.pawparazzi.START"
        const val ACTION_STOP = "io.gropp.pawparazzi.STOP"
        const val EXTRA_ROTATION = "rotation"

        @Volatile
        var isRunning = false
            private set

        private const val TAG = "DetectionService"
        private const val CHANNEL_ID = "detection"
        private const val NOTIFICATION_ID = 1

        fun startIntent(context: Context, rotation: Int): Intent =
            Intent(context, DetectionService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_ROTATION, rotation)

        fun stopIntent(context: Context): Intent =
            Intent(context, DetectionService::class.java).setAction(ACTION_STOP)
    }
}
