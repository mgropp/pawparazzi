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
import io.gropp.pawparazzi.camera.CaptureEvent
import io.gropp.pawparazzi.camera.CaptureQueue
import io.gropp.pawparazzi.camera.CaptureRequest
import io.gropp.pawparazzi.camera.FrameAnalyzer
import io.gropp.pawparazzi.camera.FrameDetections
import io.gropp.pawparazzi.camera.ObjectDetectorWrapper
import io.gropp.pawparazzi.core.detection.ActionGate
import io.gropp.pawparazzi.core.detection.Clock
import io.gropp.pawparazzi.core.detection.DetectionFilter
import io.gropp.pawparazzi.core.detection.FrameThrottler
import io.gropp.pawparazzi.core.detection.FrameWatchdog
import io.gropp.pawparazzi.core.detection.GateParams
import io.gropp.pawparazzi.core.detection.Label
import io.gropp.pawparazzi.core.detection.LandscapeRotationFilter
import io.gropp.pawparazzi.core.detection.ThermalMode
import io.gropp.pawparazzi.core.detection.ThermalPolicy
import io.gropp.pawparazzi.core.detection.ThermalStatus
import io.gropp.pawparazzi.core.detection.Tracker
import io.gropp.pawparazzi.core.detection.TrackerParams
import io.gropp.pawparazzi.core.settings.AppSettings
import io.gropp.pawparazzi.core.settings.SettingsRepository
import io.gropp.pawparazzi.state.DetectionStateRepository
import io.gropp.pawparazzi.state.PreviewSurfaceHolder
import io.gropp.pawparazzi.state.TrackUi
import io.gropp.pawparazzi.state.Warning
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@AndroidEntryPoint
class DetectionService : LifecycleService() {
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var stateRepository: DetectionStateRepository
    @Inject lateinit var surfaceHolder: PreviewSurfaceHolder
    @Inject lateinit var soundPlayer: SoundPlayer
    @Inject lateinit var photoStore: PhotoStore
    @Inject lateinit var clock: Clock

    @Volatile private var settings = AppSettings()
    @Volatile private var thermalMode = ThermalMode.NORMAL
    @Volatile private var detector: ObjectDetectorWrapper? = null

    private val pipelineLock = Any()
    private val tracker = Tracker {
        settings.let {
            TrackerParams(it.iouThreshold, it.centerDistThreshold, it.labelDedupeIou, it.labelDecay, it.gracePeriodMs)
        }
    }
    private val gate = ActionGate { settings.let { GateParams(it.cooldownMs, it.minHits) } }
    private val throttler = FrameThrottler { ThermalPolicy.effectiveFps(thermalMode, settings.fps) }
    private val thermalPolicy = ThermalPolicy()
    private val cameraMutex = Mutex()
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val frameTimestamps = ArrayDeque<Long>()

    private lateinit var watchdog: FrameWatchdog
    private lateinit var cameraController: CameraController
    private lateinit var captureQueue: CaptureQueue
    private lateinit var rotationFilter: LandscapeRotationFilter
    private var wakeLock: PowerManager.WakeLock? = null
    private var supervisorJob: Job? = null
    private var running = false
    private val thermalListener = PowerManager.OnThermalStatusChangedListener(::onThermalStatus)

    private val displayListener = object : DisplayManager.DisplayListener {
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
        thermalMode = ThermalMode.NORMAL
        rotationFilter = LandscapeRotationFilter(initialRotation)
        watchdog = FrameWatchdog(clock)
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pawparazzi:detection")
            .also { it.acquire() }
        soundPlayer.start()
        stateRepository.reset()
        stateRepository.update { it.copy(running = true) }

        val displayManager = getSystemService(DisplayManager::class.java)
        displayManager.registerDisplayListener(displayListener, null)
        (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .addThermalStatusListener(ContextCompat.getMainExecutor(this), thermalListener)

        val analyzer = FrameAnalyzer(clock, throttler, { detector }, ::onFrameArrived)
        cameraController = CameraController(this, this, analysisExecutor, analyzer, ::onCameraError)
        captureQueue = CaptureQueue(
            context = this,
            scope = lifecycleScope,
            photoStore = photoStore,
            imageCapture = { cameraController.imageCapture },
            minFreeBytes = { settings.minFreeSpaceBytes },
            capBytes = { settings.storageCapBytes },
            nowWallMs = System::currentTimeMillis,
            onEvent = ::onCaptureEvent,
        )

        supervisorJob = lifecycleScope.launch {
            settings = settingsRepository.settings.first()
            applySounds()
            detector = createDetector()
            cameraMutex.withLock { bindCamera() }
            launch { settingsRepository.settings.drop(1).collect(::onSettingsChanged) }
            launch { surfaceHolder.provider.drop(1).collect(::onSurfaceChanged) }
            launch { soundPlayer.fallbackLabels.collect { stateRepository.setWarning(Warning.SOUND_FALLBACK, it.isNotEmpty()) } }
            launch { watchdogLoop() }
        }
    }

    private suspend fun bindCamera() {
        try {
            val summary = cameraController.bind(
                settings.fps,
                rotationFilter.current,
                surfaceHolder.provider.value,
            )
            stateRepository.update {
                it.copy(captureResolution = summary.captureSize?.let { s -> "${s.width}x${s.height}" })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Camera bind failed", e)
            stateRepository.setWarning(Warning.CAMERA_ERROR, true)
        }
    }

    private fun createDetector(): ObjectDetectorWrapper = ObjectDetectorWrapper(this, clock, ::onDetections)

    private fun onFrameArrived() {
        watchdog.onFrame()
        if (watchdog.consecutiveFailures == 0) {
            stateRepository.setWarning(Warning.CAMERA_ERROR, false)
            stateRepository.setWarning(Warning.REPEATED_REBINDS, false)
        }
    }

    private fun onCameraError(error: CameraState.StateError?) {
        if (error != null) Log.w(TAG, "Camera error: code=${error.code}")
        stateRepository.setWarning(Warning.CAMERA_ERROR, error != null)
        if (running) notificationManager().notify(NOTIFICATION_ID, buildNotification())
    }

    private fun onDetections(frame: FrameDetections) {
        if (!running) return
        try {
            val s = settings
            val filtered = DetectionFilter.apply(frame.detections, s.scoreThreshold, s.maxResults)
            val triggers = synchronized(pipelineLock) {
                val update = tracker.update(filtered, frame.aspect, frame.timestampMs)
                val result = gate.evaluate(update, frame.timestampMs)
                publishTracks(update.tracks, frame)
                result
            }
            if (triggers.isNotEmpty()) {
                Log.i(TAG, "Triggers: $triggers")
                triggers.forEach { soundPlayer.play(it.label) }
                captureQueue.enqueue(CaptureRequest(triggers))
                stateRepository.update { it.copy(lastTriggerWallMs = System.currentTimeMillis()) }
                notificationManager().notify(NOTIFICATION_ID, buildNotification())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process detections", e)
        }
    }

    private fun publishTracks(tracks: List<io.gropp.pawparazzi.core.detection.Track>, frame: FrameDetections) {
        frameTimestamps.addLast(frame.timestampMs)
        while (frameTimestamps.size > FPS_WINDOW) frameTimestamps.removeFirst()
        val spanMs = frameTimestamps.last() - frameTimestamps.first()
        val fps = if (frameTimestamps.size > 1 && spanMs > 0) (frameTimestamps.size - 1) * 1000f / spanMs else 0f
        stateRepository.update {
            it.copy(
                tracks = tracks.map { t -> TrackUi(t.id, t.label, t.lastScore, t.box) },
                imageAspect = frame.aspect,
                effectiveFps = fps,
            )
        }
    }

    private fun resetPipeline() {
        synchronized(pipelineLock) {
            tracker.reset()
            gate.reset()
            frameTimestamps.clear()
        }
        stateRepository.update { it.copy(tracks = emptyList()) }
    }

    private fun onSettingsChanged(new: AppSettings) {
        val old = settings
        settings = new
        if (old.catSoundUri != new.catSoundUri || old.dogSoundUri != new.dogSoundUri) applySounds()
        if (old.fps != new.fps) {
            lifecycleScope.launch {
                cameraMutex.withLock {
                    if (thermalMode != ThermalMode.PAUSED) cameraController.onFpsChanged(new.fps)
                }
            }
        }
    }

    private fun applySounds() {
        soundPlayer.setSource(Label.CAT, settings.catSoundUri)
        soundPlayer.setSource(Label.DOG, settings.dogSoundUri)
    }

    private suspend fun onSurfaceChanged(provider: androidx.camera.core.Preview.SurfaceProvider?) {
        cameraMutex.withLock {
            try {
                cameraController.setSurfaceProvider(provider)
            } catch (e: Exception) {
                Log.e(TAG, "Preview rebind failed", e)
            }
        }
    }

    private fun onRotationMaybeChanged() {
        if (!running) return
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY) ?: return
        val accepted = rotationFilter.onRotation(display.rotation) ?: return
        Log.i(TAG, "Display rotation -> $accepted")
        cameraController.setTargetRotation(accepted)
        resetPipeline()
    }

    private fun onThermalStatus(status: Int) {
        if (!running) return
        val previous = thermalPolicy.mode
        val mode = thermalPolicy.onStatus(ThermalStatus.fromAndroid(status))
        Log.i(TAG, "Thermal status $status -> $mode")
        if (mode == previous) return
        thermalMode = mode
        stateRepository.update { it.copy(thermalMode = mode) }
        stateRepository.setWarning(Warning.THERMAL_THROTTLED, mode == ThermalMode.THROTTLED)
        stateRepository.setWarning(Warning.THERMAL_PAUSED, mode == ThermalMode.PAUSED)
        notificationManager().notify(NOTIFICATION_ID, buildNotification())
        lifecycleScope.launch {
            cameraMutex.withLock {
                when {
                    mode == ThermalMode.PAUSED -> pauseForHeat()
                    previous == ThermalMode.PAUSED -> resumeFromHeat()
                }
            }
        }
    }

    private fun pauseForHeat() {
        watchdog.suspend()
        cameraController.unbind()
        detector?.close()
        detector = null
        resetPipeline()
    }

    private suspend fun resumeFromHeat() {
        detector = createDetector()
        resetPipeline()
        bindCamera()
        watchdog.resume()
    }

    private suspend fun watchdogLoop() {
        while (true) {
            delay(WATCHDOG_POLL_MS)
            if (!watchdog.poll()) continue
            Log.w(TAG, "No frames, rebinding camera (failure #${watchdog.consecutiveFailures})")
            stateRepository.update { it.copy(rebindCount = it.rebindCount + 1) }
            if (watchdog.consecutiveFailures >= REPEATED_FAILURES_WARNING) {
                stateRepository.setWarning(Warning.REPEATED_REBINDS, true)
                notificationManager().notify(NOTIFICATION_ID, buildNotification())
            }
            cameraMutex.withLock {
                if (thermalMode != ThermalMode.PAUSED) {
                    try {
                        cameraController.rebind()
                    } catch (e: Exception) {
                        Log.e(TAG, "Watchdog rebind failed", e)
                    }
                }
            }
        }
    }

    private fun onCaptureEvent(event: CaptureEvent) {
        when (event) {
            is CaptureEvent.LowStorage -> {
                stateRepository.setWarning(Warning.LOW_STORAGE, true)
                notificationManager().notify(NOTIFICATION_ID, buildNotification())
            }
            CaptureEvent.StorageOk -> stateRepository.setWarning(Warning.LOW_STORAGE, false)
            is CaptureEvent.Saved -> Log.i(TAG, "Saved ${event.displayName}")
        }
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
        supervisorJob?.cancel()
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        (getSystemService(Context.POWER_SERVICE) as PowerManager).removeThermalStatusListener(thermalListener)
        captureQueue.close()
        cameraController.unbind()
        detector?.close()
        detector = null
        soundPlayer.release()
        analysisExecutor.shutdown()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        stateRepository.reset()
    }

    private fun notificationManager() = getSystemService(NotificationManager::class.java)

    private fun buildNotification(): Notification {
        val manager = notificationManager()
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val state = stateRepository.state.value
        val text = buildList {
            add(
                when {
                    Warning.THERMAL_PAUSED in state.warnings -> "Paused: phone too hot"
                    Warning.REPEATED_REBINDS in state.warnings -> "Camera not delivering frames, retrying"
                    else -> "Watching for cats and dogs"
                },
            )
            state.lastTriggerWallMs?.let { add("last trigger ${DateFormat.getTimeInstance().format(Date(it))}") }
            if (Warning.LOW_STORAGE in state.warnings) add("low storage, photos skipped")
        }.joinToString(" · ")
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
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
        private const val WATCHDOG_POLL_MS = 1000L
        private const val REPEATED_FAILURES_WARNING = 3
        private const val FPS_WINDOW = 10

        fun startIntent(context: Context, rotation: Int): Intent =
            Intent(context, DetectionService::class.java).setAction(ACTION_START).putExtra(EXTRA_ROTATION, rotation)

        fun stopIntent(context: Context): Intent =
            Intent(context, DetectionService::class.java).setAction(ACTION_STOP)
    }
}
