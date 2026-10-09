package io.gropp.pawparazzi.core.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SettingsValidatorTest {
    private fun clamp(settings: AppSettings) = SettingsValidator.sanitize(settings)

    @Test
    fun defaultsAreUnchanged() {
        assertThat(clamp(AppSettings())).isEqualTo(AppSettings())
    }

    @Test
    fun fps() {
        assertThat(clamp(AppSettings(fps = 0)).fps).isEqualTo(1)
        assertThat(clamp(AppSettings(fps = 99)).fps).isEqualTo(30)
    }

    @Test
    fun scoreThreshold() {
        assertThat(clamp(AppSettings(scoreThreshold = 0f)).scoreThreshold).isEqualTo(0.10f)
        assertThat(clamp(AppSettings(scoreThreshold = 1f)).scoreThreshold).isEqualTo(0.95f)
    }

    @Test
    fun cooldownSec() {
        assertThat(clamp(AppSettings(cooldownSec = 0)).cooldownSec).isEqualTo(1)
        assertThat(clamp(AppSettings(cooldownSec = 100_000)).cooldownSec).isEqualTo(3600)
    }

    @Test
    fun storageCapGb() {
        assertThat(clamp(AppSettings(storageCapGb = 0f)).storageCapGb).isEqualTo(0.1f)
        assertThat(clamp(AppSettings(storageCapGb = 500f)).storageCapGb).isEqualTo(50f)
    }

    @Test
    fun gracePeriodSec() {
        assertThat(clamp(AppSettings(gracePeriodSec = 0f)).gracePeriodSec).isEqualTo(0.5f)
        assertThat(clamp(AppSettings(gracePeriodSec = 600f)).gracePeriodSec).isEqualTo(60f)
    }

    @Test
    fun iouThreshold() {
        assertThat(clamp(AppSettings(iouThreshold = 0f)).iouThreshold).isEqualTo(0.05f)
        assertThat(clamp(AppSettings(iouThreshold = 1f)).iouThreshold).isEqualTo(0.90f)
    }

    @Test
    fun centerDistThreshold() {
        assertThat(clamp(AppSettings(centerDistThreshold = -1f)).centerDistThreshold).isEqualTo(0f)
        assertThat(clamp(AppSettings(centerDistThreshold = 5f)).centerDistThreshold).isEqualTo(2f)
    }

    @Test
    fun minHits() {
        assertThat(clamp(AppSettings(minHits = 0)).minHits).isEqualTo(1)
        assertThat(clamp(AppSettings(minHits = 50)).minHits).isEqualTo(10)
    }

    @Test
    fun labelDedupeIou() {
        assertThat(clamp(AppSettings(labelDedupeIou = 0.1f)).labelDedupeIou).isEqualTo(0.5f)
        assertThat(clamp(AppSettings(labelDedupeIou = 2f)).labelDedupeIou).isEqualTo(1.0f)
    }

    @Test
    fun minFreeSpaceGb() {
        assertThat(clamp(AppSettings(minFreeSpaceGb = 0f)).minFreeSpaceGb).isEqualTo(0.1f)
        assertThat(clamp(AppSettings(minFreeSpaceGb = 100f)).minFreeSpaceGb).isEqualTo(20f)
    }

    @Test
    fun maxResults() {
        assertThat(clamp(AppSettings(maxResults = 0)).maxResults).isEqualTo(1)
        assertThat(clamp(AppSettings(maxResults = 99)).maxResults).isEqualTo(20)
    }

    @Test
    fun labelDecay() {
        assertThat(clamp(AppSettings(labelDecay = 0f)).labelDecay).isEqualTo(0.5f)
        assertThat(clamp(AppSettings(labelDecay = 3f)).labelDecay).isEqualTo(1.0f)
    }

    @Test
    fun nanFallsBackToRangeStart() {
        assertThat(clamp(AppSettings(scoreThreshold = Float.NaN)).scoreThreshold).isEqualTo(0.10f)
    }

    @Test
    fun blankSoundUriBecomesNull() {
        assertThat(clamp(AppSettings(catSoundUri = " ", dogSoundUri = "content://x")).catSoundUri).isNull()
        assertThat(clamp(AppSettings(dogSoundUri = "content://x")).dogSoundUri).isEqualTo("content://x")
    }
}
