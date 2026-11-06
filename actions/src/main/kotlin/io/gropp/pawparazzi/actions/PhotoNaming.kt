package io.gropp.pawparazzi.actions

import io.gropp.pawparazzi.core.detection.Trigger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PhotoNaming {
    const val RELATIVE_PATH = "Pictures/Pawparazzi"
    const val MIME_TYPE = "image/jpeg"

    fun displayName(triggers: List<Trigger>, timestampMs: Long): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(timestampMs))
        if (triggers.isEmpty()) return "manual_$stamp.jpg"
        val labels = triggers.map { it.label.name.lowercase() }.distinct().joinToString("-")
        val ids = triggers.map { it.trackId }.distinct().joinToString("-")
        return "${labels}_${stamp}_t$ids.jpg"
    }
}
