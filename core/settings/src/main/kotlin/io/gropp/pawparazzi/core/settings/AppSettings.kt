package io.gropp.pawparazzi.core.settings

data class AppSettings(
    val fps: Int = 5,
    val scoreThreshold: Float = 0.5f,
    val cooldownSec: Int = 30,
    val catSoundUri: String? = null,
    val dogSoundUri: String? = null,
    val storageCapGb: Float = 2.0f,
    val gracePeriodSec: Float = 3.0f,
    val iouThreshold: Float = 0.3f,
    val centerDistThreshold: Float = 0.5f,
    val minHits: Int = 2,
    val labelDedupeIou: Float = 0.9f,
    val minFreeSpaceGb: Float = 1.0f,
    val maxResults: Int = 5,
    val labelDecay: Float = 0.9f,
) {
    val cooldownMs: Long get() = cooldownSec * 1000L
    val gracePeriodMs: Long get() = (gracePeriodSec * 1000f).toLong()
    val storageCapBytes: Long get() = (storageCapGb * BYTES_PER_GB).toLong()
    val minFreeSpaceBytes: Long get() = (minFreeSpaceGb * BYTES_PER_GB).toLong()

    private companion object {
        const val BYTES_PER_GB = 1024f * 1024f * 1024f
    }
}
