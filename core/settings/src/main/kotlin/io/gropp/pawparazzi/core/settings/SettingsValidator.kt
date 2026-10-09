package io.gropp.pawparazzi.core.settings

object SettingsValidator {
    val FPS = 1..30
    val SCORE_THRESHOLD = 0.10f..0.95f
    val COOLDOWN_SEC = 1..3600
    val STORAGE_CAP_GB = 0.1f..50f
    val GRACE_PERIOD_SEC = 0.5f..60f
    val IOU_THRESHOLD = 0.05f..0.90f
    val CENTER_DIST_THRESHOLD = 0.0f..2.0f
    val MIN_HITS = 1..10
    val LABEL_DEDUPE_IOU = 0.5f..1.0f
    val MIN_FREE_SPACE_GB = 0.1f..20f
    val MAX_RESULTS = 1..20
    val LABEL_DECAY = 0.5f..1.0f

    fun sanitize(settings: AppSettings): AppSettings = with(settings) {
        copy(
            fps = fps.coerceIn(FPS),
            scoreThreshold = scoreThreshold.clampFinite(SCORE_THRESHOLD),
            cooldownSec = cooldownSec.coerceIn(COOLDOWN_SEC),
            catSoundUri = catSoundUri?.takeIf { it.isNotBlank() },
            dogSoundUri = dogSoundUri?.takeIf { it.isNotBlank() },
            storageCapGb = storageCapGb.clampFinite(STORAGE_CAP_GB),
            gracePeriodSec = gracePeriodSec.clampFinite(GRACE_PERIOD_SEC),
            iouThreshold = iouThreshold.clampFinite(IOU_THRESHOLD),
            centerDistThreshold = centerDistThreshold.clampFinite(CENTER_DIST_THRESHOLD),
            minHits = minHits.coerceIn(MIN_HITS),
            labelDedupeIou = labelDedupeIou.clampFinite(LABEL_DEDUPE_IOU),
            minFreeSpaceGb = minFreeSpaceGb.clampFinite(MIN_FREE_SPACE_GB),
            maxResults = maxResults.coerceIn(MAX_RESULTS),
            labelDecay = labelDecay.clampFinite(LABEL_DECAY),
        )
    }

    private fun Float.clampFinite(range: ClosedFloatingPointRange<Float>): Float =
        if (isNaN()) range.start else coerceIn(range)
}
