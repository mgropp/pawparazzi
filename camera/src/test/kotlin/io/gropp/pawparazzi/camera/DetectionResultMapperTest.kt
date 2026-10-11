package io.gropp.pawparazzi.camera

import com.google.common.truth.Truth.assertThat
import io.gropp.pawparazzi.core.detection.Clock
import io.gropp.pawparazzi.core.detection.Label
import org.junit.Test

class DetectionResultMapperTest {
    private val mapper = DetectionResultMapper()

    private fun raw(
        name: String? = "cat",
        score: Float = 0.9f,
        left: Float = 0f,
        top: Float = 0f,
        right: Float = 100f,
        bottom: Float = 50f,
    ) = RawDetection(name, score, left, top, right, bottom)

    @Test
    fun pixelBoxesAreNormalizedByFrameSize() {
        mapper.recordSent(1, 200, 100)

        val frame = mapper.map(1, listOf(raw(left = 20f, top = 10f, right = 100f, bottom = 50f)))!!

        val box = frame.detections.single().box
        assertThat(box.left).isWithin(1e-6f).of(0.1f)
        assertThat(box.top).isWithin(1e-6f).of(0.1f)
        assertThat(box.right).isWithin(1e-6f).of(0.5f)
        assertThat(box.bottom).isWithin(1e-6f).of(0.5f)
    }

    @Test
    fun boxesAreClampedToUnitSquare() {
        mapper.recordSent(1, 100, 100)

        val frame = mapper.map(1, listOf(raw(left = -10f, top = -5f, right = 130f, bottom = 120f)))!!

        val box = frame.detections.single().box
        assertThat(listOf(box.left, box.top, box.right, box.bottom)).containsExactly(0f, 0f, 1f, 1f).inOrder()
    }

    @Test
    fun catAndDogMapToLabelsAndScoreIsKept() {
        mapper.recordSent(1, 100, 100)

        val frame = mapper.map(1, listOf(raw("cat", 0.8f), raw("dog", 0.6f)))!!

        assertThat(frame.detections.map { it.label }).containsExactly(Label.CAT, Label.DOG).inOrder()
        assertThat(frame.detections.map { it.score }).containsExactly(0.8f, 0.6f).inOrder()
    }

    @Test
    fun otherAndMissingCategoriesAreDropped() {
        mapper.recordSent(1, 100, 100)

        val frame = mapper.map(1, listOf(raw("bird"), raw(null), raw("dog")))!!

        assertThat(frame.detections.map { it.label }).containsExactly(Label.DOG)
    }

    @Test
    fun unknownTimestampIsDropped() {
        mapper.recordSent(1, 100, 100)

        assertThat(mapper.map(2, listOf(raw()))).isNull()
    }

    @Test
    fun timestampIsConsumedByFirstResult() {
        mapper.recordSent(1, 100, 100)
        mapper.map(1, listOf(raw()))

        assertThat(mapper.map(1, listOf(raw()))).isNull()
    }

    @Test
    fun discardedTimestampIsDropped() {
        mapper.recordSent(1, 100, 100)
        mapper.discardSent(1)

        assertThat(mapper.map(1, listOf(raw()))).isNull()
    }

    @Test
    fun clearForgetsAllFrames() {
        mapper.recordSent(1, 100, 100)
        mapper.clear()

        assertThat(mapper.map(1, listOf(raw()))).isNull()
    }

    @Test
    fun frameCarriesTimestampSizeAndAspect() {
        mapper.recordSent(7, 640, 480)

        val frame = mapper.map(7, emptyList())!!

        assertThat(frame.timestampMs).isEqualTo(7)
        assertThat(frame.imageWidth).isEqualTo(640)
        assertThat(frame.imageHeight).isEqualTo(480)
        assertThat(frame.aspect).isWithin(1e-6f).of(640f / 480f)
        assertThat(frame.detections).isEmpty()
    }

    @Test
    fun timestampsFollowClockWhenAdvancing() {
        var now = 100L
        val timestamps = MonotonicTimestamps(Clock { now })

        val first = timestamps.next()
        now = 150
        val second = timestamps.next()

        assertThat(listOf(first, second)).containsExactly(100L, 150L).inOrder()
    }

    @Test
    fun timestampsStayStrictlyIncreasingWhenClockStalls() {
        val timestamps = MonotonicTimestamps(Clock { 100L })

        assertThat(List(3) { timestamps.next() }).containsExactly(100L, 101L, 102L).inOrder()
    }

    @Test
    fun timestampsStayStrictlyIncreasingWhenClockGoesBackwards() {
        var now = 100L
        val timestamps = MonotonicTimestamps(Clock { now })

        val first = timestamps.next()
        now = 50
        val second = timestamps.next()

        assertThat(listOf(first, second)).containsExactly(100L, 101L).inOrder()
    }
}
