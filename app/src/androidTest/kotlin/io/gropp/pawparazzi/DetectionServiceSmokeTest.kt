package io.gropp.pawparazzi

import android.Manifest
import android.view.Surface
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.gropp.pawparazzi.service.DetectionService
import io.gropp.pawparazzi.state.DetectionStateRepository
import io.gropp.pawparazzi.state.DetectionUiState
import javax.inject.Inject
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class DetectionServiceSmokeTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    @Inject lateinit var stateRepository: DetectionStateRepository

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun injectDependencies() = hilt.inject()

    @Test
    fun serviceRunsDetectionAndResetsStateOnStop() {
        context.startForegroundService(DetectionService.startIntent(context, Surface.ROTATION_90))
        awaitState { it.running && it.effectiveFps > 0f }
        assertThat(stateRepository.state.value.rebindCount).isEqualTo(0)

        context.startService(DetectionService.stopIntent(context))
        awaitState { !it.running }
        assertThat(stateRepository.state.value).isEqualTo(DetectionUiState())
    }

    private fun awaitState(condition: (DetectionUiState) -> Boolean) {
        val deadline = System.currentTimeMillis() + STATE_TIMEOUT_MS
        while (
            !condition(stateRepository.state.value) && System.currentTimeMillis() < deadline
        ) Thread.sleep(100)
        assertThat(condition(stateRepository.state.value)).isTrue()
    }

    private companion object {
        const val STATE_TIMEOUT_MS = 10_000L
    }
}
