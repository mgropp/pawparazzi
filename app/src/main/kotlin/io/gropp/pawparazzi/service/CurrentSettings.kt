package io.gropp.pawparazzi.service

import io.gropp.pawparazzi.core.settings.AppSettings

class CurrentSettings(initial: AppSettings = AppSettings()) {
    @Volatile var value: AppSettings = initial
}
