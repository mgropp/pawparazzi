package io.gropp.pawparazzi.service

import androidx.camera.core.Preview
import io.gropp.pawparazzi.camera.FrameDetections
import io.gropp.pawparazzi.core.detection.Clock
import io.gropp.pawparazzi.core.detection.Detection
import io.gropp.pawparazzi.core.detection.Label
import io.gropp.pawparazzi.core.detection.NormBox

class FakeClock(var now: Long = 0L) : Clock {
    override fun nowMs(): Long = now

    fun advance(ms: Long) {
        now += ms
    }
}

fun box(cx: Float, cy: Float, size: Float = 0.2f) =
    NormBox(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)

fun detection(label: Label = Label.CAT, score: Float = 0.9f, box: NormBox = box(0.5f, 0.5f)) =
    Detection(label, score, box)

fun frame(timestampMs: Long, vararg detections: Detection) =
    FrameDetections(timestampMs, detections.toList(), imageWidth = 640, imageHeight = 480)

class FakeCamera : CameraPort {
    val calls = mutableListOf<String>()
    var captureResolution: String? = "4000x3000"
    var bindFailure: Exception? = null
    var rebindFailure: Exception? = null

    override suspend fun bind(
        fps: Int,
        rotation: Int,
        surfaceProvider: Preview.SurfaceProvider?,
    ): String? {
        calls += "bind(fps=$fps,rotation=$rotation)"
        bindFailure?.let { throw it }
        return captureResolution
    }

    override suspend fun rebind() {
        calls += "rebind"
        rebindFailure?.let { throw it }
    }

    override suspend fun onFpsChanged(fps: Int) {
        calls += "onFpsChanged($fps)"
    }

    override suspend fun setSurfaceProvider(provider: Preview.SurfaceProvider?) {
        calls += "setSurfaceProvider(${provider != null})"
    }

    override fun setTargetRotation(rotation: Int) {
        calls += "setTargetRotation($rotation)"
    }

    override fun unbind() {
        calls += "unbind"
    }

    fun count(call: String) = calls.count { it == call }
}

class FakeDetector : AutoCloseable {
    var closed = false
        private set

    override fun close() {
        closed = true
    }
}
