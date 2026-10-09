package io.gropp.pawparazzi

import android.Manifest
import android.view.Surface
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.google.common.truth.Truth.assertThat
import io.gropp.pawparazzi.service.DetectionService
import org.junit.Rule
import org.junit.Test

class DetectionServiceSmokeTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun serviceStartsAndStops() {
        context.startForegroundService(DetectionService.startIntent(context, Surface.ROTATION_90))
        waitFor { DetectionService.isRunning }
        assertThat(DetectionService.isRunning).isTrue()
        Thread.sleep(3_000)
        context.startService(DetectionService.stopIntent(context))
        waitFor { !DetectionService.isRunning }
        assertThat(DetectionService.isRunning).isFalse()
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(100)
    }
}
