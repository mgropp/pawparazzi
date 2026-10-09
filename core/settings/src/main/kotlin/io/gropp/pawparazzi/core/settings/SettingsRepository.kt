package io.gropp.pawparazzi.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class SettingsRepository(private val dataStore: DataStore<Preferences>) {
    val settings: Flow<AppSettings> = dataStore.data
        .map { SettingsValidator.sanitize(it.toSettings()) }
        .distinctUntilChanged()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { prefs ->
            prefs.writeSettings(SettingsValidator.sanitize(transform(prefs.toSettings())))
        }
    }

    suspend fun reset() {
        dataStore.edit { it.clear() }
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            fps = this[FPS] ?: d.fps,
            scoreThreshold = this[SCORE_THRESHOLD] ?: d.scoreThreshold,
            cooldownSec = this[COOLDOWN_SEC] ?: d.cooldownSec,
            catSoundUri = this[CAT_SOUND_URI],
            dogSoundUri = this[DOG_SOUND_URI],
            storageCapGb = this[STORAGE_CAP_GB] ?: d.storageCapGb,
            gracePeriodSec = this[GRACE_PERIOD_SEC] ?: d.gracePeriodSec,
            iouThreshold = this[IOU_THRESHOLD] ?: d.iouThreshold,
            centerDistThreshold = this[CENTER_DIST_THRESHOLD] ?: d.centerDistThreshold,
            minHits = this[MIN_HITS] ?: d.minHits,
            labelDedupeIou = this[LABEL_DEDUPE_IOU] ?: d.labelDedupeIou,
            minFreeSpaceGb = this[MIN_FREE_SPACE_GB] ?: d.minFreeSpaceGb,
            maxResults = this[MAX_RESULTS] ?: d.maxResults,
            labelDecay = this[LABEL_DECAY] ?: d.labelDecay,
        )
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.writeSettings(s: AppSettings) {
        this[FPS] = s.fps
        this[SCORE_THRESHOLD] = s.scoreThreshold
        this[COOLDOWN_SEC] = s.cooldownSec
        writeOrRemove(CAT_SOUND_URI, s.catSoundUri)
        writeOrRemove(DOG_SOUND_URI, s.dogSoundUri)
        this[STORAGE_CAP_GB] = s.storageCapGb
        this[GRACE_PERIOD_SEC] = s.gracePeriodSec
        this[IOU_THRESHOLD] = s.iouThreshold
        this[CENTER_DIST_THRESHOLD] = s.centerDistThreshold
        this[MIN_HITS] = s.minHits
        this[LABEL_DEDUPE_IOU] = s.labelDedupeIou
        this[MIN_FREE_SPACE_GB] = s.minFreeSpaceGb
        this[MAX_RESULTS] = s.maxResults
        this[LABEL_DECAY] = s.labelDecay
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.writeOrRemove(
        key: Preferences.Key<String>,
        value: String?,
    ) {
        if (value == null) remove(key) else this[key] = value
    }

    private companion object {
        val FPS = intPreferencesKey("fps")
        val SCORE_THRESHOLD = floatPreferencesKey("scoreThreshold")
        val COOLDOWN_SEC = intPreferencesKey("cooldownSec")
        val CAT_SOUND_URI = stringPreferencesKey("catSoundUri")
        val DOG_SOUND_URI = stringPreferencesKey("dogSoundUri")
        val STORAGE_CAP_GB = floatPreferencesKey("storageCapGb")
        val GRACE_PERIOD_SEC = floatPreferencesKey("gracePeriodSec")
        val IOU_THRESHOLD = floatPreferencesKey("iouThreshold")
        val CENTER_DIST_THRESHOLD = floatPreferencesKey("centerDistThreshold")
        val MIN_HITS = intPreferencesKey("minHits")
        val LABEL_DEDUPE_IOU = floatPreferencesKey("labelDedupeIou")
        val MIN_FREE_SPACE_GB = floatPreferencesKey("minFreeSpaceGb")
        val MAX_RESULTS = intPreferencesKey("maxResults")
        val LABEL_DECAY = floatPreferencesKey("labelDecay")
    }
}
