package io.gropp.pawparazzi.camera

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.core.content.ContextCompat
import io.gropp.pawparazzi.actions.PhotoNaming
import io.gropp.pawparazzi.actions.PhotoStore
import io.gropp.pawparazzi.core.detection.Trigger
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

data class CaptureRequest(val triggers: List<Trigger>)

sealed interface CaptureEvent {
    data class Saved(val displayName: String) : CaptureEvent
    data class LowStorage(val freeBytes: Long) : CaptureEvent
    data object StorageOk : CaptureEvent
}

class CaptureQueue(
    private val context: Context,
    scope: CoroutineScope,
    private val photoStore: PhotoStore,
    private val imageCapture: () -> ImageCapture?,
    private val minFreeBytes: () -> Long,
    private val capBytes: () -> Long,
    private val nowWallMs: () -> Long,
    private val onEvent: (CaptureEvent) -> Unit,
) {
    private val channel = Channel<CaptureRequest>(CAPACITY)

    init {
        scope.launch {
            for (request in channel) {
                try {
                    process(request)
                } catch (e: Exception) {
                    Log.e(TAG, "Capture failed", e)
                }
            }
        }
    }

    fun enqueue(request: CaptureRequest) {
        if (channel.trySend(request).isFailure) Log.w(TAG, "Capture queue full, dropping request")
    }

    fun close() {
        channel.close()
    }

    private suspend fun process(request: CaptureRequest) {
        val free = photoStore.freeBytes()
        if (free < minFreeBytes()) {
            Log.w(TAG, "Low storage ($free bytes free), skipping photo")
            onEvent(CaptureEvent.LowStorage(free))
            return
        }
        onEvent(CaptureEvent.StorageOk)
        val capture = imageCapture() ?: run {
            Log.w(TAG, "No ImageCapture bound, skipping photo")
            return
        }
        val name = PhotoNaming.displayName(request.triggers, nowWallMs())
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, PhotoNaming.MIME_TYPE)
            put(MediaStore.Images.Media.RELATIVE_PATH, PhotoNaming.RELATIVE_PATH)
        }
        val options = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values,
        ).build()
        if (takePicture(capture, options)) {
            onEvent(CaptureEvent.Saved(name))
            photoStore.enforceCap(capBytes())
        }
    }

    private suspend fun takePicture(capture: ImageCapture, options: ImageCapture.OutputFileOptions): Boolean =
        suspendCancellableCoroutine { cont ->
            capture.takePicture(
                options,
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        cont.resume(true)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.e(TAG, "takePicture failed", exception)
                        cont.resume(false)
                    }
                },
            )
        }

    private companion object {
        const val TAG = "CaptureQueue"
        const val CAPACITY = 4
    }
}
