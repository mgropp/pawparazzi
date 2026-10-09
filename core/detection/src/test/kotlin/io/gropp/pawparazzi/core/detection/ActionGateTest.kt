package io.gropp.pawparazzi.core.detection

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ActionGateTest {
    private val aspect = 4f / 3f
    private var tracker = TrackerParams(gracePeriodMs = 3000)
    private var gate = GateParams(cooldownMs = 1000, minHits = 2)
    private val trackerImpl = Tracker { tracker }
    private val gateImpl = ActionGate { gate }

    private fun step(nowMs: Long, vararg dets: Detection): List<Trigger> {
        val update = trackerImpl.update(dets.toList(), aspect, nowMs)
        return gateImpl.evaluate(update, nowMs)
    }

    @Test
    fun noTriggerBeforeMinHitsThenTriggersOnReaching() {
        assertThat(step(0, det())).isEmpty()
        assertThat(step(200, det())).containsExactly(Trigger(1, Label.CAT))
    }

    @Test
    fun minHitsOneTriggersOnCreation() {
        gate = gate.copy(minHits = 1)
        assertThat(step(0, det())).hasSize(1)
    }

    @Test
    fun minHitsChangeAppliesLive() {
        assertThat(step(0, det())).isEmpty()
        gate = gate.copy(minHits = 1)
        assertThat(step(200, det())).hasSize(1)
    }

    @Test
    fun noRetriggerBeforeCooldownThenRetriggersAtCooldown() {
        step(0, det())
        assertThat(step(200, det())).hasSize(1)
        assertThat(step(1100, det())).isEmpty()
        assertThat(step(1200, det())).hasSize(1)
        assertThat(step(1300, det())).isEmpty()
    }

    @Test
    fun noTriggerWhileCoasting() {
        step(0, det())
        step(200, det())
        assertThat(step(2000)).isEmpty()
        assertThat(step(2500)).isEmpty()
    }

    @Test
    fun twoNewTracksProduceTwoTriggers() {
        val a = det(box = box(0.25f, 0.5f))
        val b = det(Label.DOG, box = box(0.75f, 0.5f))
        step(0, a, b)
        val triggers = step(200, a, b)
        assertThat(triggers.map { it.label }).containsExactly(Label.CAT, Label.DOG)
    }

    @Test
    fun cooldownChangeAppliesLive() {
        step(0, det())
        step(200, det())
        gate = gate.copy(cooldownMs = 100)
        assertThat(step(400, det())).hasSize(1)
    }

    @Test
    fun resetForgetsCooldown() {
        step(0, det())
        step(200, det())
        gateImpl.reset()
        assertThat(step(300, det())).hasSize(1)
    }
}
