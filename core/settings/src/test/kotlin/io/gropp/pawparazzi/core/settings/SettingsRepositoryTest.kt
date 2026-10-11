package io.gropp.pawparazzi.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var storeScope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: SettingsRepository

    private val nonDefaultSettings = AppSettings(
        fps = 12,
        scoreThreshold = 0.7f,
        cooldownSec = 90,
        catSoundUri = "content://sounds/cat",
        dogSoundUri = "content://sounds/dog",
        storageCapGb = 7.5f,
        gracePeriodSec = 8.5f,
        iouThreshold = 0.45f,
        centerDistThreshold = 1.25f,
        minHits = 4,
        labelDedupeIou = 0.75f,
        minFreeSpaceGb = 3.5f,
        maxResults = 9,
        labelDecay = 0.65f,
    )

    @Before
    fun setUp() {
        storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(scope = storeScope) {
            temporaryFolder.newFile("settings.preferences_pb").also { it.delete() }
        }
        repository = SettingsRepository(dataStore)
    }

    @After
    fun tearDown() {
        storeScope.cancel()
    }

    @Test
    fun emptyStoreEmitsDefaults() = runTest {
        assertThat(repository.settings.first()).isEqualTo(AppSettings())
    }

    @Test
    fun updateRoundTripsEveryKey() = runTest {
        repository.update { nonDefaultSettings }

        assertThat(repository.settings.first()).isEqualTo(nonDefaultSettings)
    }

    @Test
    fun updateRoundTripsThroughFreshRepositoryOnSameStore() = runTest {
        repository.update { nonDefaultSettings }

        assertThat(SettingsRepository(dataStore).settings.first()).isEqualTo(nonDefaultSettings)
    }

    @Test
    fun outOfRangeValuesWrittenDirectlyAreSanitizedOnRead() = runTest {
        dataStore.edit {
            it[intPreferencesKey("fps")] = 500
            it[floatPreferencesKey("scoreThreshold")] = -3f
            it[intPreferencesKey("cooldownSec")] = 0
            it[floatPreferencesKey("storageCapGb")] = 1000f
            it[intPreferencesKey("minHits")] = 99
            it[floatPreferencesKey("labelDecay")] = Float.NaN
            it[stringPreferencesKey("catSoundUri")] = "  "
        }

        val settings = repository.settings.first()

        assertThat(settings.fps).isEqualTo(SettingsValidator.FPS.last)
        assertThat(settings.scoreThreshold).isEqualTo(SettingsValidator.SCORE_THRESHOLD.start)
        assertThat(settings.cooldownSec).isEqualTo(SettingsValidator.COOLDOWN_SEC.first)
        assertThat(settings.storageCapGb).isEqualTo(SettingsValidator.STORAGE_CAP_GB.endInclusive)
        assertThat(settings.minHits).isEqualTo(SettingsValidator.MIN_HITS.last)
        assertThat(settings.labelDecay).isEqualTo(SettingsValidator.LABEL_DECAY.start)
        assertThat(settings.catSoundUri).isNull()
    }

    @Test
    fun updateSanitizesTransformedValueBeforeWriting() = runTest {
        repository.update { it.copy(fps = 500, scoreThreshold = 5f, dogSoundUri = "") }

        val stored = dataStore.data.first()

        assertThat(stored[intPreferencesKey("fps")]).isEqualTo(SettingsValidator.FPS.last)
        assertThat(stored[floatPreferencesKey("scoreThreshold")])
            .isEqualTo(SettingsValidator.SCORE_THRESHOLD.endInclusive)
        assertThat(stored.contains(stringPreferencesKey("dogSoundUri"))).isFalse()
    }

    @Test
    fun settingSoundUrisToNullRemovesKeys() = runTest {
        repository.update { nonDefaultSettings }

        repository.update { it.copy(catSoundUri = null, dogSoundUri = null) }

        val stored = dataStore.data.first()
        assertThat(stored.contains(stringPreferencesKey("catSoundUri"))).isFalse()
        assertThat(stored.contains(stringPreferencesKey("dogSoundUri"))).isFalse()
        assertThat(repository.settings.first())
            .isEqualTo(nonDefaultSettings.copy(catSoundUri = null, dogSoundUri = null))
    }

    @Test
    fun resetRestoresDefaults() = runTest {
        repository.update { nonDefaultSettings }

        repository.reset()

        assertThat(repository.settings.first()).isEqualTo(AppSettings())
        assertThat(dataStore.data.first().asMap()).isEmpty()
    }

    @Test
    fun updateProducingIdenticalSettingsEmitsNothingNew() = runTest {
        repository.update { nonDefaultSettings }

        repository.settings.test {
            assertThat(awaitItem()).isEqualTo(nonDefaultSettings)

            repository.update { it }
            repository.update { it.copy(fps = nonDefaultSettings.fps) }
            expectNoEvents()

            repository.update { it.copy(fps = 13) }
            assertThat(awaitItem().fps).isEqualTo(13)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
