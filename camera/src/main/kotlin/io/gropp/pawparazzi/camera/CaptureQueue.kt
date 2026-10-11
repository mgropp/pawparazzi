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

interface StorageGuard {
    fun freeBytes(): Long

    suspend fun enforceCap(capBytes: Long)
}

fun interface PhotoTaker {
    suspend fun take(name: String): Boolean
}

class CaptureQueue(
    scope: CoroutineScope,
    private val storage: StorageGuard,
    private val photoTaker: PhotoTaker,
    private val minFreeBytes: () -> Long,
    private val capBytes: () -> Long,
    private val nowWallMs: () -> Long,
    private val onEvent: (CaptureEvent) -> Unit,
) {
    constructor(
        context: Context,
        scope: CoroutineScope,
        photoStore: PhotoStore,
        imageCapture: () -> ImageCapture?,
        minFreeBytes: () -> Long,
        capBytes: () -> Long,
        nowWallMs: () -> Long,
        onEvent: (CaptureEvent) -> Unit,
    ) : this(
        scope,
        PhotoStoreGuard(photoStore),
        ImageCapturePhotoTaker(context, imageCapture),
        minFreeBytes,
        capBytes,
        nowWallMs,
        onEvent,
    )

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
        val free = storage.freeBytes()
        if (free < minFreeBytes()) {
            Log.w(TAG, "Low storage ($free bytes free), skipping photo")
            onEvent(CaptureEvent.LowStorage(free))
            return
        }
        onEvent(CaptureEvent.StorageOk)
        val name = PhotoNaming.displayName(request.triggers, nowWallMs())
        if (photoTaker.take(name)) {
            onEvent(CaptureEvent.Saved(name))
            storage.enforceCap(capBytes())
        }
    }

    private companion object {
        const val CAPACITY = 4
    }
}

private class PhotoStoreGuard(private val photoStore: PhotoStore) : StorageGuard {
    override fun freeBytes(): Long = photoStore.freeBytes()

    override suspend fun enforceCap(capBytes: Long) = photoStore.enforceCap(capBytes)
}

private class ImageCapturePhotoTaker(
    private val context: Context,
    private val imageCapture: () -> ImageCapture?,
) : PhotoTaker {
    override suspend fun take(name: String): Boolean {
        val capture =
            imageCapture()
                ?: run {
                    Log.w(TAG, "No ImageCapture bound, skipping photo")
                    return false
                }
        val values =
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, PhotoNaming.MIME_TYPE)
                put(MediaStore.Images.Media.RELATIVE_PATH, PhotoNaming.RELATIVE_PATH)
            }
        val options =
            ImageCapture.OutputFileOptions.Builder(
                    context.contentResolver,
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values,
                )
                .build()
        return takePicture(capture, options)
    }

    private suspend fun takePicture(
        capture: ImageCapture,
        options: ImageCapture.OutputFileOptions,
    ): Boolean = suspendCancellableCoroutine { cont ->
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
}

private const val TAG = "CaptureQueue"
