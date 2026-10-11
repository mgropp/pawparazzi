package io.gropp.pawparazzi.service

import android.util.Log

interface DetectionLogger {
    fun info(message: String)

    fun warn(message: String)

    fun error(message: String, cause: Throwable? = null)
}

object AndroidDetectionLogger : DetectionLogger {
    private const val TAG = "DetectionService"

    override fun info(message: String) {
        Log.i(TAG, message)
    }

    override fun warn(message: String) {
        Log.w(TAG, message)
    }

    override fun error(message: String, cause: Throwable?) {
        Log.e(TAG, message, cause)
    }
}

object SilentDetectionLogger : DetectionLogger {
    override fun info(message: String) = Unit

    override fun warn(message: String) = Unit

    override fun error(message: String, cause: Throwable?) = Unit
}
