package io.gropp.pawparazzi.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import io.gropp.pawparazzi.R

private data class Credit(
    val name: String,
    val author: String,
    val license: String,
    val url: String,
    val note: String? = null,
)

private val appCredit = Credit(
    name = "Pawparazzi",
    author = "Martin Gropp",
    license = "GNU General Public License v3.0",
    url = "https://www.gnu.org/licenses/gpl-3.0.html",
)

private val soundCredits = listOf(
    Credit("Cat sound (cat_default.wav)", "qubodup", "CC0 1.0", "https://freesound.org/people/qubodup/sounds/813119/"),
    Credit("Dog sound (dog_default.wav)", "boop_7", "CC0 1.0", "https://freesound.org/s/684369/", note = "Cropped"),
)

private val modelCredits = listOf(
    Credit(
        "EfficientDet-Lite0 object detection model",
        "Google (MediaPipe)",
        "Apache License 2.0",
        "https://ai.google.dev/edge/mediapipe/solutions/vision/object_detector",
    ),
)

@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val libraries by produceLibraries(R.raw.aboutlibraries)

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onBack) { Text("Back") }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Header("This app") }
            item { CreditItem(appCredit) }
            item { Header("Sounds") }
            items(soundCredits) { CreditItem(it) }
            item { Header("Model") }
            items(modelCredits) { CreditItem(it) }
            item { Header("Libraries") }
            items(libraries?.libraries.orEmpty(), key = { it.uniqueId }) { LibraryItem(it) }
        }
    }
}

@Composable
private fun Header(title: String) {
    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun CreditItem(credit: Credit) {
    val uriHandler = LocalUriHandler.current
    Column(Modifier.fillMaxWidth()) {
        Text(credit.name, style = MaterialTheme.typography.titleMedium)
        Text("${credit.author} · ${credit.license}", style = MaterialTheme.typography.bodyMedium)
        credit.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        TextButton(onClick = { uriHandler.openUri(credit.url) }) { Text(credit.url, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun LibraryItem(library: Library) {
    val uriHandler = LocalUriHandler.current
    var expanded by rememberSaveable(library.uniqueId) { mutableStateOf(false) }
    val licenseNames = library.licenses.joinToString { it.name }
    val author = library.developers.mapNotNull { it.name }.joinToString().ifEmpty { library.organization?.name.orEmpty() }
    Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Text(library.name, style = MaterialTheme.typography.titleMedium)
        Text(
            listOfNotNull(library.artifactVersion, author.ifEmpty { null }, licenseNames.ifEmpty { null }).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (expanded) {
            library.website?.let { url ->
                TextButton(onClick = { uriHandler.openUri(url) }) { Text(url, style = MaterialTheme.typography.bodySmall) }
            }
            library.licenses.forEach { license ->
                license.licenseContent?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
