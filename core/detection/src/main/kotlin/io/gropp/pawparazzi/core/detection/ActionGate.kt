package io.gropp.pawparazzi.core.detection

data class GateParams(val cooldownMs: Long = 30_000L, val minHits: Int = 2)

class ActionGate(private val params: () -> GateParams = { GateParams() }) {
    private val lastTriggeredMs = mutableMapOf<Long, Long>()

    fun evaluate(update: TrackerUpdate, nowMs: Long): List<Trigger> {
        lastTriggeredMs.keys.removeAll(update.prunedIds)
        val p = params()
        val activeIds = update.createdIds + update.matchedIds
        val triggers = update.tracks
            .filter { it.id in activeIds && it.isConfirmed(p.minHits) }
            .filter { track ->
                val last = lastTriggeredMs[track.id]
                last == null || nowMs - last >= p.cooldownMs
            }
            .map { Trigger(it.id, it.label) }
        triggers.forEach { lastTriggeredMs[it.trackId] = nowMs }
        return triggers
    }

    fun reset() {
        lastTriggeredMs.clear()
    }
}
