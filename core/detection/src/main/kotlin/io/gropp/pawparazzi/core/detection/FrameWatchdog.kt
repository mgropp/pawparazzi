package io.gropp.pawparazzi.core.detection

class FrameWatchdog(
    private val clock: Clock,
    private val initialTimeoutMs: Long = 10_000L,
    private val maxTimeoutMs: Long = 300_000L,
) {
    private var deadlineMs = clock.nowMs() + initialTimeoutMs
    private var timeoutMs = initialTimeoutMs
    private var suspended = false

    var consecutiveFailures: Int = 0
        private set

    @Synchronized
    fun onFrame() {
        consecutiveFailures = 0
        timeoutMs = initialTimeoutMs
        deadlineMs = clock.nowMs() + timeoutMs
    }

    @Synchronized
    fun suspend() {
        suspended = true
    }

    @Synchronized
    fun resume() {
        suspended = false
        onFrame()
    }

    @Synchronized
    fun poll(): Boolean {
        if (suspended) return false
        val now = clock.nowMs()
        if (now < deadlineMs) return false
        consecutiveFailures++
        timeoutMs = (timeoutMs * 2).coerceAtMost(maxTimeoutMs)
        deadlineMs = now + timeoutMs
        return true
    }
}
