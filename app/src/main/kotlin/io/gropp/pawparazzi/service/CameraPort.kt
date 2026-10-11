package io.gropp.pawparazzi.service

import androidx.camera.core.Preview
import io.gropp.pawparazzi.camera.CameraController

interface CameraPort {
    suspend fun bind(fps: Int, rotation: Int, surfaceProvider: Preview.SurfaceProvider?): String?
    suspend fun rebind()
    suspend fun onFpsChanged(fps: Int)
    suspend fun setSurfaceProvider(provider: Preview.SurfaceProvider?)
    fun setTargetRotation(rotation: Int)
    fun unbind()
}

class CameraControllerPort(private val controller: CameraController) : CameraPort {
    override suspend fun bind(fps: Int, rotation: Int, surfaceProvider: Preview.SurfaceProvider?): String? =
        controller.bind(fps, rotation, surfaceProvider).captureSize?.let { "${it.width}x${it.height}" }

    override suspend fun rebind() {
        controller.rebind()
    }

    override suspend fun onFpsChanged(fps: Int) = controller.onFpsChanged(fps)

    override suspend fun setSurfaceProvider(provider: Preview.SurfaceProvider?) =
        controller.setSurfaceProvider(provider)

    override fun setTargetRotation(rotation: Int) = controller.setTargetRotation(rotation)

    override fun unbind() = controller.unbind()
}
