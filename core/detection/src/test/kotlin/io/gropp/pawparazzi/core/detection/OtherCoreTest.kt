package io.gropp.pawparazzi.core.detection

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DetectionFilterTest {
    private val dets = listOf(0.9f, 0.4f, 0.7f, 0.6f).map { det(score = it) }

    @Test
    fun dropsBelowThreshold() {
        assertThat(DetectionFilter.apply(dets, 0.5f, 20).map { it.score }).containsExactly(0.9f, 0.7f, 0.6f).inOrder()
    }

    @Test
    fun keepsTopMaxResults() {
        assertThat(DetectionFilter.apply(dets, 0.1f, 2).map { it.score }).containsExactly(0.9f, 0.7f).inOrder()
    }

    @Test
    fun changesApplyOnNextCall() {
        assertThat(DetectionFilter.apply(dets, 0.8f, 5)).hasSize(1)
        assertThat(DetectionFilter.apply(dets, 0.3f, 5)).hasSize(4)
    }
}

class FrameThrottlerTest {
    private var fps = 5
    private val throttler = FrameThrottler { fps }

    @Test
    fun respectsFps() {
        assertThat(throttler.shouldProcess(0)).isTrue()
        assertThat(throttler.shouldProcess(100)).isFalse()
        assertThat(throttler.shouldProcess(199)).isFalse()
        assertThat(throttler.shouldProcess(200)).isTrue()
    }

    @Test
    fun fpsChangeAppliesLive() {
        throttler.shouldProcess(0)
        assertThat(throttler.shouldProcess(500)).isTrue()
        fps = 1
        assertThat(throttler.shouldProcess(1000)).isFalse()
        assertThat(throttler.shouldProcess(1500)).isTrue()
    }
}

class LandscapeRotationFilterTest {
    @Test
    fun acceptsLandscapeIgnoresPortraitAndKeepsLast() {
        val filter = LandscapeRotationFilter(initial = LandscapeRotationFilter.ROTATION_90)
        assertThat(filter.onRotation(0)).isNull()
        assertThat(filter.onRotation(2)).isNull()
        assertThat(filter.current).isEqualTo(1)
        assertThat(filter.onRotation(3)).isEqualTo(3)
        assertThat(filter.onRotation(0)).isNull()
        assertThat(filter.current).isEqualTo(3)
        assertThat(filter.onRotation(3)).isNull()
        assertThat(filter.onRotation(1)).isEqualTo(1)
    }
}

class FrameWatchdogTest {
    private val clock = FakeClock()
    private val watchdog = FrameWatchdog(clock)

    @Test
    fun firesAfterTimeoutWithExponentialBackoff() {
        clock.advance(9_999)
        assertThat(watchdog.poll()).isFalse()
        clock.advance(1)
        assertThat(watchdog.poll()).isTrue()
        clock.advance(19_999)
        assertThat(watchdog.poll()).isFalse()
        clock.advance(1)
        assertThat(watchdog.poll()).isTrue()
        clock.advance(40_000)
        assertThat(watchdog.poll()).isTrue()
        assertThat(watchdog.consecutiveFailures).isEqualTo(3)
    }

    @Test
    fun backoffCapsAtFiveMinutes() {
        repeat(12) {
            clock.advance(300_000)
            watchdog.poll()
        }
        clock.advance(299_999)
        assertThat(watchdog.poll()).isFalse()
        clock.advance(1)
        assertThat(watchdog.poll()).isTrue()
    }

    @Test
    fun resetsWhenFramesResume() {
        clock.advance(10_000)
        watchdog.poll()
        watchdog.onFrame()
        assertThat(watchdog.consecutiveFailures).isEqualTo(0)
        clock.advance(9_999)
        assertThat(watchdog.poll()).isFalse()
        clock.advance(1)
        assertThat(watchdog.poll()).isTrue()
        clock.advance(10_000)
        assertThat(watchdog.poll()).isFalse()
    }

    @Test
    fun suspendedWatchdogNeverFiresAndResumeRestartsTimer() {
        watchdog.suspend()
        clock.advance(100_000)
        assertThat(watchdog.poll()).isFalse()
        watchdog.resume()
        clock.advance(9_999)
        assertThat(watchdog.poll()).isFalse()
        clock.advance(1)
        assertThat(watchdog.poll()).isTrue()
    }
}

class ThermalPolicyTest {
    private val policy = ThermalPolicy()

    @Test
    fun transitionsAtSevereAndCritical() {
        assertThat(policy.onStatus(ThermalStatus.MODERATE)).isEqualTo(ThermalMode.NORMAL)
        assertThat(policy.onStatus(ThermalStatus.SEVERE)).isEqualTo(ThermalMode.THROTTLED)
        assertThat(policy.onStatus(ThermalStatus.CRITICAL)).isEqualTo(ThermalMode.PAUSED)
    }

    @Test
    fun criticalFromNormalPauses() {
        assertThat(policy.onStatus(ThermalStatus.EMERGENCY)).isEqualTo(ThermalMode.PAUSED)
    }

    @Test
    fun hysteresisOnWayDown() {
        policy.onStatus(ThermalStatus.CRITICAL)
        assertThat(policy.onStatus(ThermalStatus.SEVERE)).isEqualTo(ThermalMode.PAUSED)
        assertThat(policy.onStatus(ThermalStatus.MODERATE)).isEqualTo(ThermalMode.THROTTLED)
        assertThat(policy.onStatus(ThermalStatus.MODERATE)).isEqualTo(ThermalMode.THROTTLED)
        assertThat(policy.onStatus(ThermalStatus.LIGHT)).isEqualTo(ThermalMode.NORMAL)
    }

    @Test
    fun pausedDroppingToLightGoesToNormal() {
        policy.onStatus(ThermalStatus.CRITICAL)
        assertThat(policy.onStatus(ThermalStatus.LIGHT)).isEqualTo(ThermalMode.NORMAL)
    }

    @Test
    fun throttledCapsFpsAtOne() {
        assertThat(ThermalPolicy.effectiveFps(ThermalMode.NORMAL, 10)).isEqualTo(10)
        assertThat(ThermalPolicy.effectiveFps(ThermalMode.THROTTLED, 10)).isEqualTo(1)
    }

    @Test
    fun mapsAndroidStatusInts() {
        assertThat(ThermalStatus.fromAndroid(3)).isEqualTo(ThermalStatus.SEVERE)
        assertThat(ThermalStatus.fromAndroid(4)).isEqualTo(ThermalStatus.CRITICAL)
    }
}

class FpsRangeSelectorTest {
    @Test
    fun picksSmallestUpperAtLeastTargetPreferringVariable() {
        val ranges = listOf(FpsRange(15, 15), FpsRange(30, 30), FpsRange(10, 30), FpsRange(15, 24), FpsRange(24, 24))
        assertThat(FpsRangeSelector.select(ranges, 5)).isEqualTo(FpsRange(15, 15))
        assertThat(FpsRangeSelector.select(ranges, 20)).isEqualTo(FpsRange(15, 24))
        assertThat(FpsRangeSelector.select(ranges, 30)).isEqualTo(FpsRange(10, 30))
    }

    @Test
    fun fallsBackToHighestWhenNoneEligible() {
        assertThat(FpsRangeSelector.select(listOf(FpsRange(5, 5), FpsRange(7, 7)), 30)).isEqualTo(FpsRange(7, 7))
    }

    @Test
    fun emptyReturnsNull() {
        assertThat(FpsRangeSelector.select(emptyList(), 5)).isNull()
    }
}
