package io.gropp.pawparazzi.core.detection

class FrameThrottler(private val fps: () -> Int) {
    private var lastProcessedMs: Long? = null

    fun shouldProcess(nowMs: Long): Boolean {
        val last = lastProcessedMs
        val intervalMs = 1000L / fps().coerceAtLeast(1)
        if (last != null && nowMs - last < intervalMs) return false
        lastProcessedMs = nowMs
        return true
    }

    fun reset() {
        lastProcessedMs = null
    }
}
