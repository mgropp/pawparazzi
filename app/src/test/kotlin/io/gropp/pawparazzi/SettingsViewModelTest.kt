package io.gropp.pawparazzi

import com.google.common.truth.Truth.assertThat
import io.gropp.pawparazzi.actions.SoundCheck
import io.gropp.pawparazzi.core.detection.Label
import io.gropp.pawparazzi.core.settings.AppSettings
import io.gropp.pawparazzi.core.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private var nextCheck: SoundCheck = SoundCheck.Ok
    private val checkedUris = mutableListOf<String>()
    private val soundChecker = SoundChecker {
        checkedUris += it
        nextCheck
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newRepository() = SettingsRepository(InMemoryPreferencesDataStore())

    private fun newViewModel(repository: SettingsRepository = newRepository()) =
        SettingsViewModel(repository, soundChecker)

    @Test
    fun okCheckWritesUriToCatLabel() =
        runTest(testDispatcher) {
            val repository = newRepository()
            val viewModel = newViewModel(repository)

            viewModel.pickSound(Label.CAT, "content://cat.mp3")
            advanceUntilIdle()

            val settings = repository.settings.first()
            assertThat(settings.catSoundUri).isEqualTo("content://cat.mp3")
            assertThat(settings.dogSoundUri).isNull()
            assertThat(checkedUris).containsExactly("content://cat.mp3")
        }

    @Test
    fun okCheckWritesUriToDogLabel() =
        runTest(testDispatcher) {
            val repository = newRepository()
            val viewModel = newViewModel(repository)

            viewModel.pickSound(Label.DOG, "content://dog.mp3")
            advanceUntilIdle()

            val settings = repository.settings.first()
            assertThat(settings.dogSoundUri).isEqualTo("content://dog.mp3")
            assertThat(settings.catSoundUri).isNull()
        }

    @Test
    fun tooLongCheckSetsMessageAndLeavesSettingsUntouched() =
        runTest(testDispatcher) {
            val repository = newRepository()
            val viewModel = newViewModel(repository)
            nextCheck = SoundCheck.TooLong(7_500)

            viewModel.pickSound(Label.CAT, "content://long.mp3")
            advanceUntilIdle()

            assertThat(viewModel.message.value).isEqualTo("Sound is 7.5 s long; the limit is 5 s.")
            assertThat(repository.settings.first()).isEqualTo(AppSettings())
        }

    @Test
    fun unreadableCheckSetsMessageAndLeavesSettingsUntouched() =
        runTest(testDispatcher) {
            val repository = newRepository()
            val viewModel = newViewModel(repository)
            nextCheck = SoundCheck.Unreadable

            viewModel.pickSound(Label.DOG, "content://broken.mp3")
            advanceUntilIdle()

            assertThat(viewModel.message.value).isEqualTo("That file could not be read as audio.")
            assertThat(repository.settings.first()).isEqualTo(AppSettings())
        }

    @Test
    fun dismissMessageClearsMessage() =
        runTest(testDispatcher) {
            val repository = newRepository()
            val viewModel = newViewModel(repository)
            nextCheck = SoundCheck.Unreadable
            viewModel.pickSound(Label.CAT, "content://broken.mp3")
            advanceUntilIdle()

            viewModel.dismissMessage()

            assertThat(viewModel.message.value).isNull()
        }

    @Test
    fun clearSoundNullsOnlyTheGivenLabel() =
        runTest(testDispatcher) {
            val repository = newRepository()
            val viewModel = newViewModel(repository)
            viewModel.pickSound(Label.CAT, "content://cat.mp3")
            viewModel.pickSound(Label.DOG, "content://dog.mp3")
            advanceUntilIdle()

            viewModel.clearSound(Label.CAT)
            advanceUntilIdle()

            val settings = repository.settings.first()
            assertThat(settings.catSoundUri).isNull()
            assertThat(settings.dogSoundUri).isEqualTo("content://dog.mp3")
        }

    @Test
    fun clearSoundNullsDogLabelOnly() =
        runTest(testDispatcher) {
            val repository = newRepository()
            val viewModel = newViewModel(repository)
            viewModel.pickSound(Label.CAT, "content://cat.mp3")
            viewModel.pickSound(Label.DOG, "content://dog.mp3")
            advanceUntilIdle()

            viewModel.clearSound(Label.DOG)
            advanceUntilIdle()

            val settings = repository.settings.first()
            assertThat(settings.dogSoundUri).isNull()
            assertThat(settings.catSoundUri).isEqualTo("content://cat.mp3")
        }
}
