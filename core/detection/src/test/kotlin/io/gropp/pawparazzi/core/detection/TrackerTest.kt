package io.gropp.pawparazzi.core.detection

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TrackerTest {
    private val aspect = 4f / 3f
    private var params = TrackerParams()
    private val tracker = Tracker { params }

    @Test
    fun newDetectionCreatesTrack() {
        val update = tracker.update(listOf(det()), aspect, 0)
        assertThat(update.tracks).hasSize(1)
        assertThat(update.createdIds).containsExactly(1L)
        assertThat(update.tracks.single().hits).isEqualTo(1)
    }

    @Test
    fun overlappingBoxesKeepSameId() {
        tracker.update(listOf(det(box = box(0.5f, 0.5f))), aspect, 0)
        val update = tracker.update(listOf(det(box = box(0.52f, 0.5f))), aspect, 200)
        assertThat(update.tracks.single().id).isEqualTo(1L)
        assertThat(update.matchedIds).containsExactly(1L)
        assertThat(update.tracks.single().hits).isEqualTo(2)
    }

    @Test
    fun labelFlipKeepsTrackAndFollowsScores() {
        tracker.update(listOf(det(Label.CAT, 0.9f)), aspect, 0)
        var update = tracker.update(listOf(det(Label.DOG, 0.6f)), aspect, 200)
        assertThat(update.tracks).hasSize(1)
        assertThat(update.tracks.single().label).isEqualTo(Label.CAT)
        repeat(10) { update = tracker.update(listOf(det(Label.DOG, 0.9f)), aspect, 400L + it * 200) }
        assertThat(update.tracks.single().id).isEqualTo(1L)
        assertThat(update.tracks.single().label).isEqualTo(Label.DOG)
    }

    @Test
    fun highlyOverlappingDifferentLabelsAreDeduped() {
        val update = tracker.update(
            listOf(det(Label.CAT, 0.8f, box(0.5f, 0.5f)), det(Label.DOG, 0.6f, box(0.5f, 0.5f))),
            aspect,
            0,
        )
        assertThat(update.tracks).hasSize(1)
        assertThat(update.tracks.single().label).isEqualTo(Label.CAT)
    }

    @Test
    fun differentLabelsAtIou08ProduceTwoTracks() {
        val a = NormBox(0.0f, 0.0f, 0.5f, 0.5f)
        val b = NormBox(0.0f, 0.0f, 0.5f, 0.5f * 0.8f / 1f)
        assertThat(a.iou(b)).isWithin(0.001f).of(0.8f)
        val update = tracker.update(listOf(det(Label.CAT, 0.8f, a), det(Label.DOG, 0.7f, b)), aspect, 0)
        assertThat(update.tracks).hasSize(2)
    }

    @Test
    fun dedupeCanBeDisabled() {
        params = params.copy(labelDedupeIou = 1.0f)
        val update = tracker.update(
            listOf(det(Label.CAT, 0.8f, box(0.5f, 0.5f)), det(Label.DOG, 0.6f, box(0.5f, 0.5f))),
            aspect,
            0,
        )
        assertThat(update.tracks).hasSize(2)
    }

    @Test
    fun resetClearsTracksAndIdsKeepIncreasing() {
        tracker.update(listOf(det()), aspect, 0)
        tracker.reset()
        val update = tracker.update(listOf(det()), aspect, 200)
        assertThat(update.tracks.single().id).isEqualTo(2L)
        assertThat(update.createdIds).containsExactly(2L)
    }

    @Test
    fun fastMotionMatchesViaCenterDistanceFallback() {
        tracker.update(listOf(det(box = box(0.2f, 0.5f))), aspect, 0)
        val moved = box(0.32f, 0.5f)
        assertThat(box(0.2f, 0.5f).iou(moved)).isLessThan(params.iouThreshold)
        val update = tracker.update(listOf(det(box = moved)), aspect, 200)
        assertThat(update.tracks.single().id).isEqualTo(1L)
        assertThat(update.matchedIds).containsExactly(1L)
    }

    @Test
    fun fastMotionCreatesNewTrackWhenFallbackDisabled() {
        params = params.copy(centerDistThreshold = 0f)
        tracker.update(listOf(det(box = box(0.2f, 0.5f))), aspect, 0)
        val update = tracker.update(listOf(det(box = box(0.32f, 0.5f))), aspect, 200)
        assertThat(update.createdIds).containsExactly(2L)
        assertThat(update.tracks).hasSize(2)
    }

    @Test
    fun centerFallbackIsAspectCorrected() {
        val a = box(0.5f, 0.5f)
        val pixels = 100f
        val width = 640f
        val height = 480f
        val horizontal = box(0.5f + pixels / width, 0.5f)
        val vertical = box(0.5f, 0.5f + pixels / height)
        val dh = a.centerDistance(horizontal, aspect) / maxOf(a.diagonal(aspect), horizontal.diagonal(aspect))
        val dv = a.centerDistance(vertical, aspect) / maxOf(a.diagonal(aspect), vertical.diagonal(aspect))
        assertThat(dh).isWithin(0.0001f).of(dv)
    }

    @Test
    fun labelDecayLetsTrackSwitchLabelButNotWhenPlainSum() {
        fun run(decay: Float): Label {
            val t = Tracker { TrackerParams(labelDecay = decay) }
            var update = t.update(listOf(det(Label.CAT, 0.9f)), aspect, 0)
            for (i in 1..30) update = t.update(listOf(det(Label.CAT, 0.9f)), aspect, i * 100L)
            for (i in 31..40) update = t.update(listOf(det(Label.DOG, 0.9f)), aspect, i * 100L)
            return update.tracks.single().label
        }
        assertThat(run(0.8f)).isEqualTo(Label.DOG)
        assertThat(run(1.0f)).isEqualTo(Label.CAT)
    }

    @Test
    fun trackSurvivesGracePeriodThenIsPruned() {
        tracker.update(listOf(det()), aspect, 0)
        var update = tracker.update(emptyList(), aspect, 3000)
        assertThat(update.tracks).hasSize(1)
        assertThat(update.prunedIds).isEmpty()
        update = tracker.update(emptyList(), aspect, 3001)
        assertThat(update.tracks).isEmpty()
        assertThat(update.prunedIds).containsExactly(1L)
    }

    @Test
    fun reappearingAfterPruningGetsNewId() {
        tracker.update(listOf(det()), aspect, 0)
        tracker.update(emptyList(), aspect, 5000)
        val update = tracker.update(listOf(det()), aspect, 5200)
        assertThat(update.tracks.single().id).isEqualTo(2L)
    }

    @Test
    fun twoAnimalsSideBySideGetTwoTracks() {
        val dets = listOf(det(box = box(0.25f, 0.5f)), det(Label.DOG, box = box(0.75f, 0.5f)))
        val first = tracker.update(dets, aspect, 0)
        assertThat(first.tracks).hasSize(2)
        val second = tracker.update(dets, aspect, 200)
        assertThat(second.tracks.map { it.id }).containsExactly(1L, 2L)
        assertThat(second.matchedIds).containsExactly(1L, 2L)
    }

    @Test
    fun iouThresholdBoundaryIsInclusive() {
        val a = NormBox(0f, 0f, 0.4f, 0.4f)
        val b = NormBox(0.1f, 0f, 0.5f, 0.4f)
        val iou = a.iou(b)
        tracker.update(listOf(det(box = a)), aspect, 0)
        params = params.copy(iouThreshold = iou - 0.001f, centerDistThreshold = 0f)
        assertThat(tracker.update(listOf(det(box = b)), aspect, 100).createdIds).isEmpty()
        val strict = Tracker { TrackerParams(iouThreshold = iou + 0.001f, centerDistThreshold = 0f) }
        strict.update(listOf(det(box = a)), aspect, 0)
        assertThat(strict.update(listOf(det(box = b)), aspect, 100).createdIds).hasSize(1)
    }
}
