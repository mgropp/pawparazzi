package io.gropp.pawparazzi.core.detection

data class FpsRange(val lower: Int, val upper: Int) {
    val isVariable: Boolean get() = lower < upper
}

object FpsRangeSelector {
    private const val MIN_TARGET_FPS = 10

    fun select(available: List<FpsRange>, fps: Int): FpsRange? {
        val target = maxOf(fps, MIN_TARGET_FPS)
        val eligible = available.filter { it.upper >= target }
        val smallestUpper = eligible.minOfOrNull { it.upper } ?: return available.maxByOrNull { it.upper }
        val sameUpper = eligible.filter { it.upper == smallestUpper }
        return sameUpper.filter { it.isVariable }.minByOrNull { it.lower } ?: sameUpper.first()
    }
}
