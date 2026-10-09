package io.gropp.pawparazzi.core.detection

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class Label { CAT, DOG }

data class NormBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val area: Float get() = max(0f, width) * max(0f, height)
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun iou(other: NormBox): Float {
        val interW = min(right, other.right) - max(left, other.left)
        val interH = min(bottom, other.bottom) - max(top, other.top)
        if (interW <= 0f || interH <= 0f) return 0f
        val inter = interW * interH
        val union = area + other.area - inter
        return if (union <= 0f) 0f else inter / union
    }

    fun diagonal(aspect: Float): Float = hypot(width * aspect, height)

    fun centerDistance(other: NormBox, aspect: Float): Float =
        hypot((centerX - other.centerX) * aspect, centerY - other.centerY)
}

data class Detection(val label: Label, val score: Float, val box: NormBox)

data class Trigger(val trackId: Long, val label: Label)
