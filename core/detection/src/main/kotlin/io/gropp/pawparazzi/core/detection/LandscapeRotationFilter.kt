package io.gropp.pawparazzi.core.detection

class LandscapeRotationFilter(initial: Int = ROTATION_90) {
    var current: Int = if (isLandscape(initial)) initial else ROTATION_90
        private set

    fun onRotation(rotation: Int): Int? {
        if (!isLandscape(rotation) || rotation == current) return null
        current = rotation
        return rotation
    }

    companion object {
        const val ROTATION_90 = 1
        const val ROTATION_270 = 3

        fun isLandscape(rotation: Int): Boolean = rotation == ROTATION_90 || rotation == ROTATION_270
    }
}
