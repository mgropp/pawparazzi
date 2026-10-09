package io.gropp.pawparazzi.ui

import android.graphics.Color as AndroidColor
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.gropp.pawparazzi.MainViewModel
import io.gropp.pawparazzi.core.detection.ThermalMode
import io.gropp.pawparazzi.state.DetectionUiState
import io.gropp.pawparazzi.state.PreviewSurfaceHolder
import io.gropp.pawparazzi.state.Warning
import java.text.DateFormat
import java.util.Date
import kotlin.math.max

data class SystemStatus(
    val cameraGranted: Boolean = true,
    val notificationsGranted: Boolean = true,
    val batteryExempt: Boolean = true,
)

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    surfaceHolder: PreviewSurfaceHolder,
    systemStatus: SystemStatus,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onRequestPermissions: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val mediaMuted by viewModel.mediaMuted.collectAsStateWithLifecycle()

    Row(Modifier.fillMaxSize().safeDrawingPadding()) {
        Box(Modifier.weight(1f).fillMaxHeight()) {
            val context = LocalContext.current
            val previewView = remember {
                PreviewView(context).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }
            }
            LifecycleStartEffect(previewView) {
                surfaceHolder.set(previewView.surfaceProvider)
                onStopOrDispose { surfaceHolder.set(null) }
            }
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            DetectionOverlay(state, Modifier.fillMaxSize())
        }
        Column(
            Modifier.width(320.dp).fillMaxHeight().padding(12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!systemStatus.cameraGranted) {
                Text("Camera permission is required to watch for animals.")
                Button(onClick = onRequestPermissions) { Text("Grant permission") }
                OutlinedButton(onClick = onOpenAppSettings) { Text("App settings") }
            } else if (state.running) {
                Button(onClick = onStop) { Text("Stop") }
            } else {
                Button(onClick = onStart) { Text("Start") }
            }
            OutlinedButton(onClick = onOpenSettings) { Text("Settings") }
            StatusLine(state)
            WarningList(state, mediaMuted, systemStatus, onRequestPermissions, onRequestBatteryExemption)
        }
    }
}

@Composable
private fun StatusLine(state: DetectionUiState) {
    val lastTrigger = state.lastTriggerWallMs?.let { DateFormat.getTimeInstance().format(Date(it)) } ?: "never"
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(if (state.running) "Running" else "Stopped", style = MaterialTheme.typography.titleMedium)
        Text("FPS: ${"%.1f".format(state.effectiveFps)}")
        Text("Tracks: ${state.tracks.size}")
        Text("Last trigger: $lastTrigger")
        Text("Capture: ${state.captureResolution ?: "-"}")
        Text("Camera rebinds: ${state.rebindCount}")
    }
}

@Composable
private fun WarningList(
    state: DetectionUiState,
    mediaMuted: Boolean,
    systemStatus: SystemStatus,
    onRequestPermissions: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
) {
    val warn = MaterialTheme.colorScheme.error
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (mediaMuted) Text("Media volume is 0, sounds are silent.", color = warn)
        if (Warning.SOUND_FALLBACK in state.warnings) Text("A custom sound could not be loaded; the default is used.", color = warn)
        if (Warning.CAMERA_ERROR in state.warnings) Text("Camera error.", color = warn)
        if (Warning.REPEATED_REBINDS in state.warnings) Text("Camera is not delivering frames; retrying.", color = warn)
        if (state.thermalMode == ThermalMode.PAUSED) Text("Paused: phone too hot.", color = warn)
        else if (state.thermalMode == ThermalMode.THROTTLED) Text("Phone is hot: analysis limited to 1 FPS.", color = warn)
        if (Warning.LOW_STORAGE in state.warnings) Text("Low storage: photos are skipped.", color = warn)
        if (!systemStatus.notificationsGranted) {
            Text("Notification permission missing; the status notification is hidden.", color = warn)
            OutlinedButton(onClick = onRequestPermissions) { Text("Allow notifications") }
        }
        if (!systemStatus.batteryExempt) {
            Text("Not exempt from battery optimization. See README, \"Keeping Pawparazzi alive\".", color = warn)
            OutlinedButton(onClick = onRequestBatteryExemption) { Text("Exempt app") }
        }
    }
}

@Composable
private fun DetectionOverlay(state: DetectionUiState, modifier: Modifier) {
    val paint = remember {
        android.graphics.Paint().apply {
            color = AndroidColor.WHITE
            textSize = 36f
            isAntiAlias = true
            setShadowLayer(4f, 0f, 0f, AndroidColor.BLACK)
        }
    }
    Canvas(modifier) {
        val imageW = state.imageAspect * 1000f
        val imageH = 1000f
        val scale = max(size.width / imageW, size.height / imageH)
        val offsetX = (size.width - imageW * scale) / 2f
        val offsetY = (size.height - imageH * scale) / 2f
        state.tracks.forEach { track ->
            val left = offsetX + track.box.left * imageW * scale
            val top = offsetY + track.box.top * imageH * scale
            val width = (track.box.right - track.box.left) * imageW * scale
            val height = (track.box.bottom - track.box.top) * imageH * scale
            drawRect(Color.Green, Offset(left, top), Size(width, height), style = Stroke(width = 4f))
            drawContext.canvas.nativeCanvas.drawText(
                "${track.label.name.lowercase()} ${"%.2f".format(track.score)} #${track.id}",
                left + 6f,
                top + 36f,
                paint,
            )
        }
    }
}
