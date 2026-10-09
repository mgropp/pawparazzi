package io.gropp.pawparazzi.actions

data class StoredPhoto(val id: Long, val sizeBytes: Long)

object CapPlanner {
    fun selectForDeletion(oldestFirst: List<StoredPhoto>, capBytes: Long): List<StoredPhoto> {
        var total = oldestFirst.sumOf { it.sizeBytes }
        val victims = mutableListOf<StoredPhoto>()
        for (photo in oldestFirst) {
            if (total <= capBytes) break
            victims += photo
            total -= photo.sizeBytes
        }
        return victims
    }
}
