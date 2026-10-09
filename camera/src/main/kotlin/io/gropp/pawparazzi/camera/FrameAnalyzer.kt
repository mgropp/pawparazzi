package io.gropp.pawparazzi.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import io.gropp.pawparazzi.core.detection.Clock
import io.gropp.pawparazzi.core.detection.FrameThrottler

class FrameAnalyzer(
    private val clock: Clock,
    private val throttler: FrameThrottler,
    private val detector: () -> ObjectDetectorWrapper?,
    private val onFrameArrived: () -> Unit,
) : ImageAnalysis.Analyzer {
    override fun analyze(image: ImageProxy) {
        try {
            onFrameArrived()
            if (!throttler.shouldProcess(clock.nowMs())) return
            val active = detector() ?: return
            val upright = image.toUprightBitmap()
            active.detect(upright)
        } catch (e: Exception) {
            Log.e(TAG, "Analyzer failed on frame", e)
        } finally {
            image.close()
        }
    }

    private fun ImageProxy.toUprightBitmap(): Bitmap {
        val bitmap = toBitmap()
        val degrees = imageInfo.rotationDegrees
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private companion object {
        const val TAG = "FrameAnalyzer"
    }
}
