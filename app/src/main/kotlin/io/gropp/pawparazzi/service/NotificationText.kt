package io.gropp.pawparazzi.service

import io.gropp.pawparazzi.state.DetectionUiState
import io.gropp.pawparazzi.state.Warning
import java.text.DateFormat
import java.util.Date

fun notificationText(
    state: DetectionUiState,
    formatTime: (Long) -> String = { DateFormat.getTimeInstance().format(Date(it)) },
): String = buildList {
    add(
        when {
            Warning.THERMAL_PAUSED in state.warnings -> "Paused: phone too hot"
            Warning.REPEATED_REBINDS in state.warnings -> "Camera not delivering frames, retrying"
            else -> "Watching for cats and dogs"
        },
    )
    state.lastTriggerWallMs?.let { add("last trigger ${formatTime(it)}") }
    if (Warning.LOW_STORAGE in state.warnings) add("low storage, photos skipped")
}.joinToString(" · ")
