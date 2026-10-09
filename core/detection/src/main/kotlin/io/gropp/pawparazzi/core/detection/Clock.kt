package io.gropp.pawparazzi.core.detection

fun interface Clock {
    fun nowMs(): Long
}
