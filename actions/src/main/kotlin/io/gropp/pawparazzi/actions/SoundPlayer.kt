package io.gropp.pawparazzi.actions

import android.content.Context
import android.database.ContentObserver
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.SoundPool
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.gropp.pawparazzi.core.detection.Label
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

sealed interface SoundCheck {
    data object Ok : SoundCheck
    data class TooLong(val durationMs: Long) : SoundCheck
    data object Unreadable : SoundCheck
}

@Singleton
class SoundPlayer @Inject constructor(@ApplicationContext private val context: Context) {
    private class Slot(var uri: String?, var sampleId: Int = 0, var loaded: Boolean = false)

    private val lock = Any()
    private val slots = mutableMapOf<Label, Slot>()
    private var soundPool: SoundPool? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _mediaMuted = MutableStateFlow(readMuted())
    val mediaMuted: StateFlow<Boolean> = _mediaMuted.asStateFlow()

    private val _fallbackLabels = MutableStateFlow<Set<Label>>(emptySet())
    val fallbackLabels: StateFlow<Set<Label>> = _fallbackLabels.asStateFlow()

    private val volumeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = refreshMuted()
    }

    init {
        context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver)
    }

    fun refreshMuted() {
        _mediaMuted.value = readMuted()
    }

    fun start() = synchronized(lock) {
        if (soundPool != null) return@synchronized
        soundPool = SoundPool.Builder()
            .setMaxStreams(MAX_STREAMS)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build()
            .also { it.setOnLoadCompleteListener(::onLoadComplete) }
    }

    fun release() = synchronized(lock) {
        soundPool?.release()
        soundPool = null
        slots.clear()
        _fallbackLabels.value = emptySet()
    }

    fun setSource(label: Label, uri: String?) = synchronized(lock) {
        val pool = soundPool ?: return@synchronized
        val existing = slots[label]
        if (existing != null && existing.uri == uri) return@synchronized
        existing?.let { pool.unload(it.sampleId) }
        val slot = Slot(uri)
        slots[label] = slot
        if (uri == null) {
            _fallbackLabels.value -= label
            loadDefault(pool, label, slot)
        } else {
            val loaded = runCatching { loadUser(pool, uri) }
            loaded.onSuccess { slot.sampleId = it }
            loaded.onFailure {
                Log.w(TAG, "Cannot load $uri for $label, using default", it)
                fallBackToDefault(pool, label, slot)
            }
        }
    }

    fun play(label: Label) {
        val playId = synchronized(lock) {
            val slot = slots[label]
            if (slot == null || !slot.loaded) {
                Log.w(TAG, "Sound for $label not loaded, skipping")
                return
            }
            soundPool?.play(slot.sampleId, 1f, 1f, 1, 0, 1f)
        }
        if (playId == 0) Log.w(TAG, "SoundPool refused to play $label")
    }

    suspend fun check(uri: Uri): SoundCheck = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            when {
                durationMs == null -> SoundCheck.Unreadable
                durationMs > MAX_DURATION_MS -> SoundCheck.TooLong(durationMs)
                else -> SoundCheck.Ok
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot read $uri", e)
            SoundCheck.Unreadable
        } finally {
            retriever.release()
        }
    }

    private fun readMuted(): Boolean = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0

    private fun loadUser(pool: SoundPool, uri: String): Int {
        val afd = context.contentResolver.openAssetFileDescriptor(Uri.parse(uri), "r")
            ?: error("No file descriptor for $uri")
        return afd.use { pool.load(it, 1) }.also { check(it != 0) { "SoundPool.load returned 0" } }
    }

    private fun loadDefault(pool: SoundPool, label: Label, slot: Slot) {
        slot.sampleId = pool.load(context, defaultResource(label), 1)
    }

    private fun fallBackToDefault(pool: SoundPool, label: Label, slot: Slot) {
        _fallbackLabels.value += label
        slot.loaded = false
        pool.unload(slot.sampleId)
        slot.uri = null
        loadDefault(pool, label, slot)
    }

    private fun onLoadComplete(pool: SoundPool, sampleId: Int, status: Int) = synchronized(lock) {
        val entry = slots.entries.firstOrNull { it.value.sampleId == sampleId } ?: return@synchronized
        val slot = entry.value
        if (status == 0) {
            slot.loaded = true
        } else if (slot.uri != null) {
            Log.w(TAG, "Sample for ${entry.key} failed to load (status $status), using default")
            fallBackToDefault(pool, entry.key, slot)
        } else {
            Log.e(TAG, "Default sample for ${entry.key} failed to load (status $status)")
        }
    }

    private fun defaultResource(label: Label): Int = when (label) {
        Label.CAT -> R.raw.cat_default
        Label.DOG -> R.raw.dog_default
    }

    companion object {
        private const val TAG = "SoundPlayer"
        private const val MAX_STREAMS = 4
        const val MAX_DURATION_MS = 5_000L
    }
}
