package io.gropp.pawparazzi.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.gropp.pawparazzi.SettingsViewModel
import io.gropp.pawparazzi.core.detection.Label
import io.gropp.pawparazzi.core.settings.SettingsValidator as Limits

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit, onOpenLicenses: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val update = viewModel::update

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBack) { Text("Back") }
            OutlinedButton(onClick = viewModel::reset) { Text("Reset to defaults") }
        }
        message?.let {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = viewModel::dismissMessage) { Text("OK") }
            }
        }

        Section("Basic")
        IntSetting("Analysis FPS", settings.fps, Limits.FPS) { v -> update { it.copy(fps = v) } }
        FloatSetting("Score threshold", settings.scoreThreshold, Limits.SCORE_THRESHOLD) { v -> update { it.copy(scoreThreshold = v) } }
        IntSetting("Cooldown (s)", settings.cooldownSec, Limits.COOLDOWN_SEC) { v -> update { it.copy(cooldownSec = v) } }
        SoundSetting("Cat sound", settings.catSoundUri, viewModel, Label.CAT)
        SoundSetting("Dog sound", settings.dogSoundUri, viewModel, Label.DOG)
        FloatSetting("Storage cap (GB)", settings.storageCapGb, Limits.STORAGE_CAP_GB) { v -> update { it.copy(storageCapGb = v) } }

        Section("Advanced")
        FloatSetting("Grace period (s)", settings.gracePeriodSec, Limits.GRACE_PERIOD_SEC) { v -> update { it.copy(gracePeriodSec = v) } }
        FloatSetting("IoU threshold", settings.iouThreshold, Limits.IOU_THRESHOLD) { v -> update { it.copy(iouThreshold = v) } }
        FloatSetting("Center distance threshold (0 = off)", settings.centerDistThreshold, Limits.CENTER_DIST_THRESHOLD) { v -> update { it.copy(centerDistThreshold = v) } }
        IntSetting("Min hits", settings.minHits, Limits.MIN_HITS) { v -> update { it.copy(minHits = v) } }
        FloatSetting("Label dedupe IoU (1.0 = off)", settings.labelDedupeIou, Limits.LABEL_DEDUPE_IOU) { v -> update { it.copy(labelDedupeIou = v) } }
        FloatSetting("Min free space (GB)", settings.minFreeSpaceGb, Limits.MIN_FREE_SPACE_GB) { v -> update { it.copy(minFreeSpaceGb = v) } }
        IntSetting("Max results", settings.maxResults, Limits.MAX_RESULTS) { v -> update { it.copy(maxResults = v) } }
        FloatSetting("Label decay (1.0 = none)", settings.labelDecay, Limits.LABEL_DECAY) { v -> update { it.copy(labelDecay = v) } }

        Section("About")
        OutlinedButton(onClick = onOpenLicenses) { Text("Open source licenses") }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun IntSetting(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Column {
        Text("$label: $value")
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = if (range.last - range.first <= 40) range.last - range.first - 1 else 0,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun FloatSetting(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Text("$label: ${"%.2f".format(value)}")
        Slider(
            value = value,
            onValueChange = { onChange((it * 100f).toInt() / 100f) },
            valueRange = range,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SoundSetting(label: String, uri: String?, viewModel: SettingsViewModel, soundLabel: Label) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked != null) {
            context.contentResolver.takePersistableUriPermission(picked, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            viewModel.pickSound(soundLabel, picked)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(label)
            Text(uri ?: "Bundled default", style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = { picker.launch(arrayOf("audio/*")) }) { Text("Choose") }
        if (uri != null) OutlinedButton(onClick = { viewModel.clearSound(soundLabel) }) { Text("Default") }
    }
}

