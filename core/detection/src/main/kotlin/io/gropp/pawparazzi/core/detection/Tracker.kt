package io.gropp.pawparazzi.core.detection

import kotlin.math.max

data class Track(
    val id: Long,
    val box: NormBox,
    val labelScores: Map<Label, Float>,
    val hits: Int,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val lastScore: Float = 0f,
) {
    val label: Label get() = labelScores.maxBy { it.value }.key

    fun isConfirmed(minHits: Int): Boolean = hits >= minHits
}

data class TrackerParams(
    val iouThreshold: Float = 0.3f,
    val centerDistThreshold: Float = 0.5f,
    val labelDedupeIou: Float = 0.9f,
    val labelDecay: Float = 0.9f,
    val gracePeriodMs: Long = 3000L,
)

data class TrackerUpdate(
    val tracks: List<Track>,
    val createdIds: Set<Long>,
    val matchedIds: Set<Long>,
    val prunedIds: Set<Long>,
)

class Tracker(private val params: () -> TrackerParams = { TrackerParams() }) {
    private var tracks: List<Track> = emptyList()
    private var nextId = 1L

    fun update(detections: List<Detection>, aspect: Float, nowMs: Long): TrackerUpdate {
        val p = params()
        val dets = dedupe(detections, p.labelDedupeIou)

        val pairs = greedyMatch(
            candidates = buildList {
                for (t in tracks.indices) for (d in dets.indices) {
                    val iou = tracks[t].box.iou(dets[d].box)
                    if (iou >= p.iouThreshold) add(Candidate(t, d, iou))
                }
            },
            higherIsBetter = true,
            usedTracks = mutableSetOf(),
            usedDetections = mutableSetOf(),
        ).toMutableList()

        if (p.centerDistThreshold > 0f) {
            val usedTracks = pairs.mapTo(mutableSetOf()) { it.first }
            val usedDetections = pairs.mapTo(mutableSetOf()) { it.second }
            pairs += greedyMatch(
                candidates = buildList {
                    for (t in tracks.indices) {
                        if (t in usedTracks) continue
                        for (d in dets.indices) {
                            if (d in usedDetections) continue
                            val tb = tracks[t].box
                            val db = dets[d].box
                            val norm = max(tb.diagonal(aspect), db.diagonal(aspect))
                            if (norm <= 0f) continue
                            val dist = tb.centerDistance(db, aspect) / norm
                            if (dist <= p.centerDistThreshold) add(Candidate(t, d, dist))
                        }
                    }
                },
                higherIsBetter = false,
                usedTracks = usedTracks,
                usedDetections = usedDetections,
            )
        }

        val matchedByTrack = pairs.associate { it.first to it.second }
        val matchedDetections = pairs.mapTo(mutableSetOf()) { it.second }
        val matchedIds = mutableSetOf<Long>()
        val createdIds = mutableSetOf<Long>()
        val prunedIds = mutableSetOf<Long>()
        val next = mutableListOf<Track>()

        tracks.forEachIndexed { index, track ->
            val detIndex = matchedByTrack[index]
            if (detIndex != null) {
                val updated = matchTrack(track, dets[detIndex], p.labelDecay, nowMs)
                matchedIds += updated.id
                next += updated
            } else if (nowMs - track.lastSeenMs > p.gracePeriodMs) {
                prunedIds += track.id
            } else {
                next += track
            }
        }

        dets.forEachIndexed { index, det ->
            if (index in matchedDetections) return@forEachIndexed
            val track = Track(
                id = nextId++,
                box = det.box,
                labelScores = mapOf(det.label to det.score),
                hits = 1,
                firstSeenMs = nowMs,
                lastSeenMs = nowMs,
                lastScore = det.score,
            )
            createdIds += track.id
            next += track
        }

        tracks = next
        return TrackerUpdate(next, createdIds, matchedIds, prunedIds)
    }

    fun reset() {
        tracks = emptyList()
    }

    private fun dedupe(detections: List<Detection>, labelDedupeIou: Float): List<Detection> {
        if (labelDedupeIou >= 1f) return detections
        val kept = mutableListOf<Detection>()
        for (det in detections.sortedByDescending { it.score }) {
            val duplicate = kept.any { it.label != det.label && it.box.iou(det.box) >= labelDedupeIou }
            if (!duplicate) kept += det
        }
        return kept
    }

    private fun matchTrack(track: Track, det: Detection, decay: Float, nowMs: Long): Track {
        val scores = track.labelScores.mapValues { it.value * decay }.toMutableMap()
        scores[det.label] = (scores[det.label] ?: 0f) + det.score
        return track.copy(
            box = det.box,
            labelScores = scores,
            hits = track.hits + 1,
            lastSeenMs = nowMs,
            lastScore = det.score,
        )
    }

    private class Candidate(val track: Int, val detection: Int, val value: Float)

    private fun greedyMatch(
        candidates: List<Candidate>,
        higherIsBetter: Boolean,
        usedTracks: MutableSet<Int>,
        usedDetections: MutableSet<Int>,
    ): List<Pair<Int, Int>> {
        val comparator = if (higherIsBetter) compareByDescending<Candidate> { it.value } else compareBy { it.value }
        val result = mutableListOf<Pair<Int, Int>>()
        for (c in candidates.sortedWith(comparator.thenBy { it.track }.thenBy { it.detection })) {
            if (c.track in usedTracks || c.detection in usedDetections) continue
            usedTracks += c.track
            usedDetections += c.detection
            result += c.track to c.detection
        }
        return result
    }
}
