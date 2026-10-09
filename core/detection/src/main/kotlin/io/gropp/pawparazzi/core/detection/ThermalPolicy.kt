package io.gropp.pawparazzi.core.detection

enum class ThermalStatus {
    NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN;

    companion object {
        fun fromAndroid(value: Int): ThermalStatus = entries.getOrElse(value) { if (value < 0) NONE else SHUTDOWN }
    }
}

enum class ThermalMode { NORMAL, THROTTLED, PAUSED }

class ThermalPolicy {
    var mode: ThermalMode = ThermalMode.NORMAL
        private set

    fun onStatus(status: ThermalStatus): ThermalMode {
        mode = when (mode) {
            ThermalMode.NORMAL -> when {
                status >= ThermalStatus.CRITICAL -> ThermalMode.PAUSED
                status >= ThermalStatus.SEVERE -> ThermalMode.THROTTLED
                else -> ThermalMode.NORMAL
            }
            ThermalMode.THROTTLED -> when {
                status >= ThermalStatus.CRITICAL -> ThermalMode.PAUSED
                status >= ThermalStatus.MODERATE -> ThermalMode.THROTTLED
                else -> ThermalMode.NORMAL
            }
            ThermalMode.PAUSED -> when {
                status >= ThermalStatus.SEVERE -> ThermalMode.PAUSED
                status >= ThermalStatus.MODERATE -> ThermalMode.THROTTLED
                else -> ThermalMode.NORMAL
            }
        }
        return mode
    }

    companion object {
        const val THROTTLED_FPS = 1

        fun effectiveFps(mode: ThermalMode, fps: Int): Int =
            if (mode == ThermalMode.NORMAL) fps else minOf(fps, THROTTLED_FPS)
    }
}
