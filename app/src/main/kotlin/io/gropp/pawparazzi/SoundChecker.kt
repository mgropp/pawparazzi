package io.gropp.pawparazzi

import io.gropp.pawparazzi.actions.SoundCheck

fun interface SoundChecker {
    suspend fun check(uri: String): SoundCheck
}
