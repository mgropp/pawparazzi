package io.gropp.pawparazzi.actions

import com.google.common.truth.Truth.assertThat
import io.gropp.pawparazzi.core.detection.Label
import io.gropp.pawparazzi.core.detection.Trigger
import java.util.TimeZone
import org.junit.Test

class CapPlannerTest {
    private val photos = listOf(StoredPhoto(1, 100), StoredPhoto(2, 100), StoredPhoto(3, 100))

    @Test
    fun nothingDeletedUnderCap() {
        assertThat(CapPlanner.selectForDeletion(photos, 300)).isEmpty()
    }

    @Test
    fun deletesOldestUntilUnderCap() {
        assertThat(CapPlanner.selectForDeletion(photos, 150).map { it.id }).containsExactly(1L, 2L).inOrder()
        assertThat(CapPlanner.selectForDeletion(photos, 299).map { it.id }).containsExactly(1L)
    }

    @Test
    fun zeroCapDeletesEverything() {
        assertThat(CapPlanner.selectForDeletion(photos, 0)).hasSize(3)
    }
}

class PhotoNamingTest {
    @Test
    fun joinsDistinctLabelsAndTrackIds() {
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            val name = PhotoNaming.displayName(
                listOf(Trigger(12, Label.CAT), Trigger(13, Label.DOG), Trigger(14, Label.CAT)),
                1_791_468_902_123L,
            )
            assertThat(name).isEqualTo("cat-dog_20261008_141502_123_t12-13-14.jpg")
        } finally {
            TimeZone.setDefault(previous)
        }
    }
}
