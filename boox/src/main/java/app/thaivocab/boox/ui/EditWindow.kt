package app.thaivocab.boox.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.thaivocab.core.data.CaptureContext
import app.thaivocab.core.data.Transcription
import app.thaivocab.core.data.VocabEntry

/** Opens when the user taps an entry: correct the text, change the label, remove or delete. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditWindow(
    entry: VocabEntry,
    contextOptions: List<CaptureContext>,
    onDismiss: () -> Unit,
    onSave: (thai: String, notes: String, context: CaptureContext) -> Unit,
    onDelete: () -> Unit,
    onReadAgain: () -> Unit,
    onRetry: () -> Unit,
    onRemoveImage: () -> Unit,
) {
    var thai by remember { mutableStateOf(entry.thai.orEmpty()) }
    var notes by remember { mutableStateOf(entry.notes.orEmpty()) }
    var context by remember { mutableStateOf(entry.context) }
    var confirmRemove by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (entry.imageFile != null) {
                    HandwritingImage(entry.imageFile, Modifier.fillMaxWidth().height(120.dp))
                    if (entry.englishImageFile != null) {
                        Spacer(Modifier.height(6.dp))
                        HandwritingImage(entry.englishImageFile, Modifier.fillMaxWidth().height(80.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                }
                OutlinedTextField(
                    value = thai, onValueChange = { thai = it },
                    label = { Text("Thai") },
                    textStyle = TextStyle(fontSize = 26.sp),
                    modifier = Modifier.fillMaxWidth(),
                )
                val choices = (listOfNotNull(entry.thai) + entry.alternatives).distinct().filter { it != thai }
                if (choices.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Other possibilities (tap to use):", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        choices.forEach { c -> AssistChip(onClick = { thai = c }, label = { Text(c, fontSize = 20.sp) }) }
                    }
                }
                entry.writtenText?.takeIf { entry.writtenScript == "phonetic" }?.let {
                    Text("You wrote: $it", style = MaterialTheme.typography.bodyLarge)
                }
                entry.writtenEnglish?.let { Text("Your translation: $it", style = MaterialTheme.typography.bodyLarge) }
                if (entry.imageFile != null) {
                    if (entry.transcription == Transcription.FAILED) {
                        TextButton(onClick = onRetry) { Text("Try reading again") }
                    }
                    TextButton(onClick = onReadAgain) {
                        Text(if (entry.writtenScript == "phonetic") "Read again as Thai script" else "Read again as phonetic")
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = notes, onValueChange = { notes = it },
                    label = { Text("Notes") },
                    modifier = Modifier.fillMaxWidth(),
                )
                // The image can be removed; the word stays.
                if (entry.imageFile != null) {
                    TextButton(onClick = { if (confirmRemove) onRemoveImage() else confirmRemove = true }) {
                        Text(if (confirmRemove) "Really remove the handwriting image?" else "Remove handwriting image")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Context:", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // The current context of the word stays selectable, even if its label was removed.
                    val options = if (contextOptions.any { it.id == entry.context.id }) contextOptions
                        else contextOptions + entry.context
                    options.forEach { c ->
                        FilterChip(selected = c.id == context.id, onClick = { context = c }, label = { Text(c.label) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(thai, notes, context) }) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton(onClick = { if (confirmDelete) onDelete() else confirmDelete = true }) {
                    Text(if (confirmDelete) "Really delete?" else "Delete")
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** Adds a word with the keyboard, without handwriting. */
@Composable
fun TypeWindow(onDismiss: () -> Unit, onSave: (thai: String, notes: String) -> Unit) {
    var thai by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Type a word") },
        text = {
            Column {
                OutlinedTextField(
                    value = thai, onValueChange = { thai = it },
                    label = { Text("Thai") },
                    textStyle = TextStyle(fontSize = 26.sp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = notes, onValueChange = { notes = it },
                    label = { Text("Notes (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(thai, notes) }, enabled = thai.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
