package app.booktts.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.booktts.R
import app.booktts.container

const val SOURCE_URL = "https://github.com/novikov-p-v/book-to-speech"
const val PRIVACY_URL = "$SOURCE_URL/blob/main/PRIVACY.md"

/** Third-party software in the app (name, licence). */
private val components = listOf(
    "sherpa-onnx (k2-fsa)" to "Apache-2.0",
    "espeak-ng" to "GPL-3.0",
    "piper-phonemize" to "MIT",
    "ONNX Runtime (Microsoft)" to "MIT",
    "PdfBox-Android" to "Apache-2.0",
    "juniversalchardet" to "MPL-1.1 / GPL / LGPL",
    "Apache Commons Compress" to "Apache-2.0",
    "AndroidX, Jetpack Compose, Media3" to "Apache-2.0",
    "Kotlin, kotlinx" to "Apache-2.0",
    "RUAccent ё dictionary (Denis Petrov)" to "MIT",
)

@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val installed by context.container.voices.installed.collectAsStateWithLifecycle()
    val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${stringResource(R.string.app_name)} ${version.orEmpty()}") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.about_text), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL)))
                }) { Text(stringResource(R.string.source_code)) }

                Section(stringResource(R.string.about_privacy_title))
                Text(stringResource(R.string.about_privacy), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_URL)))
                }) { Text(stringResource(R.string.privacy_policy)) }

                Section(stringResource(R.string.about_components))
                components.forEach { (name, lic) -> Text("$name — $lic", style = MaterialTheme.typography.bodySmall) }

                if (installed.isNotEmpty()) {
                    Section(stringResource(R.string.about_voices))
                    val catalog = context.container.voices.catalog
                    installed.forEach { m ->
                        // models installed before licences were recorded: take them from the catalog
                        val cat = catalog.firstOrNull { it.id == m.id }
                        val details = listOf(m.meta.license.ifEmpty { cat?.license.orEmpty() }, m.meta.credit.ifEmpty { cat?.credit.orEmpty() }).filter { it.isNotEmpty() }.joinToString(". ")
                        Text(
                            m.name + if (details.isNotEmpty()) " — $details" else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        Modifier.padding(top = 12.dp, bottom = 2.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}
