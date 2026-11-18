package app.booktts.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.booktts.R
import app.booktts.container
import app.booktts.voice.Voice
import app.booktts.voice.VoiceRef
import app.booktts.voice.languageName

/**
 * Lists every installed voice that can read [lang], with a preview button.
 * [autoLabel] non-null adds an "automatic" option (selected when [selected] is null).
 */
@Composable
fun VoicePickerDialog(
    lang: String,
    selected: VoiceRef?,
    autoLabel: String?,
    onPick: (Voice?) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = LocalContext.current.container
    val installed by c.voices.installed.collectAsStateWithLifecycle()
    val options = remember(installed, lang) { c.voices.voicesFor(lang) }
    val active by c.preview.active.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        c.playback.pause()
        onDispose { c.preview.stop() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.voice_for_language, languageName(lang))) },
        text = {
            if (options.isEmpty()) {
                Text(stringResource(R.string.no_voice_for_language))
            } else {
                LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    if (autoLabel != null) {
                        item {
                            OptionRow(autoLabel, selected == null, previewing = null, onPreview = null) { onPick(null) }
                        }
                    }
                    items(options, key = { it.ref.encode() }) { v ->
                        val key = c.preview.keyOf(v, lang)
                        OptionRow(
                            label = v.label,
                            selected = selected == v.ref,
                            previewing = active == key,
                            onPreview = { if (active == key) c.preview.stop() else c.preview.play(v, lang) },
                        ) { onPick(v) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun OptionRow(
    label: String,
    selected: Boolean,
    previewing: Boolean?,
    onPreview: (() -> Unit)?,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
        if (onPreview != null) {
            IconButton(onClick = onPreview) {
                if (previewing == true) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.PlayArrow, stringResource(R.string.preview))
                }
            }
        }
    }
}
