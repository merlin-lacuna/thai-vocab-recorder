package app.thaivocab.recorder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import app.thaivocab.core.data.CustomContext
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import app.thaivocab.core.data.CaptureContext
import app.thaivocab.core.data.VocabEntry
import app.thaivocab.core.sync.SupabaseConfig
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditDialog(
    entry: VocabEntry,
    onDismiss: () -> Unit,
    onSave: (thai: String, notes: String, context: CaptureContext) -> Unit,
    onDelete: () -> Unit,
    onPlay: () -> Unit,
    onRetry: () -> Unit = {},
    contextOptions: List<CaptureContext> = CaptureContext.BUILT_IN,
    onRemoveOwnRecording: () -> Unit = {},
    onRemoveNativeClip: () -> Unit = {},
) {
    var thai by remember { mutableStateOf(entry.thai.orEmpty()) }
    var confirmRemove by remember { mutableStateOf<String?>(null) }
    var notes by remember { mutableStateOf(entry.notes.orEmpty()) }
    var context by remember { mutableStateOf(entry.context) }
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("Edit")
                if ((entry.audioFile != null || entry.nativeAudioFile != null)) {
                    IconButton(onClick = onPlay) { Icon(Icons.Filled.PlayArrow, contentDescription = "Play") }
                }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = thai, onValueChange = { thai = it },
                    label = { Text("Thai") },
                    textStyle = TextStyle(fontSize = 24.sp),
                    modifier = Modifier.fillMaxWidth(),
                )
                val choices = (listOfNotNull(entry.thai) + entry.alternatives).distinct().filter { it != thai }
                if (choices.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Other possibilities (tap to use):", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        choices.forEach { c -> AssistChip(onClick = { thai = c }, label = { Text(c, fontSize = 18.sp) }) }
                    }
                }
                if ((entry.audioFile != null || entry.nativeAudioFile != null)) {
                    TextButton(onClick = onRetry) { Text("Try recognition again") }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = notes, onValueChange = { notes = it },
                    label = { Text("Notes (meaning, where you heard it…)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                // Clips can be removed one by one; the word stays.
                if (entry.audioFile != null) {
                    TextButton(onClick = {
                        if (confirmRemove == "own") onRemoveOwnRecording() else confirmRemove = "own"
                    }) {
                        Text(if (confirmRemove == "own") "Really remove my recording?" else "Remove my recording",
                            color = MaterialTheme.colorScheme.error)
                    }
                }
                if (entry.nativeAudioFile != null) {
                    TextButton(onClick = {
                        if (confirmRemove == "native") onRemoveNativeClip() else confirmRemove = "native"
                    }) {
                        Text(if (confirmRemove == "native") "Really remove native clip?" else "Remove native clip",
                            color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Context:", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // The word's current context stays selectable, even if its label was removed.
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
                    Text(if (confirmDelete) "Really delete?" else "Delete", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
fun TypeDialog(onDismiss: () -> Unit, onSave: (thai: String, notes: String) -> Unit) {
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
                    textStyle = TextStyle(fontSize = 24.sp),
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

/** One-time connection to the Supabase project. */
@Composable
fun SetupDialog(vm: MainViewModel, state: SupabaseConfig.State, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(state.url) }
    var key by remember { mutableStateOf(state.apiKey) }
    var email by remember { mutableStateOf(state.email) }
    var password by remember { mutableStateOf(vm.draftPassword) }

    // Save every change at once, so the input survives if the window closes.
    LaunchedEffect(url, key, email) { vm.saveSetupDraft(url.trim(), key.trim(), email.trim()) }
    LaunchedEffect(password) { vm.draftPassword = password }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        // A tap next to the window must not close it; use "Later" or the back gesture.
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(if (state.connected) "Database connection" else "Connect to database") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (state.connected) {
                    Text("Connected as ${state.email}")
                    state.lastError?.let {
                        Spacer(Modifier.height(8.dp))
                        Text("Last sync problem: $it", color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("To change the account, disconnect first.", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(
                        "Values from your Supabase project (see README). Needed only once.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    state.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    OutlinedTextField(url, { url = it }, label = { Text("Project URL") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(key, { key = it }, label = { Text("Publishable (anon) key") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth())
                    result?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            if (state.connected) {
                TextButton(onClick = { vm.syncNow(); onDismiss() }) { Text("Sync now") }
            } else {
                TextButton(
                    enabled = !busy && url.isNotBlank() && key.isNotBlank() && email.isNotBlank() && password.isNotBlank(),
                    onClick = {
                        busy = true
                        scope.launch {
                            val error = vm.connect(url, key, email, password)
                            busy = false
                            if (error == null) onDismiss() else result = error
                        }
                    },
                ) { Text(if (busy) "Connecting…" else "Connect") }
            }
        },
        dismissButton = {
            if (state.connected) {
                TextButton(onClick = { vm.disconnect() }) { Text("Disconnect", color = MaterialTheme.colorScheme.error) }
            } else {
                TextButton(onClick = onDismiss) { Text("Later") }
            }
        },
    )
}

/** Add, rename and remove the user's own context labels. Built-in labels are fixed. */
@Composable
fun LabelsDialog(
    labels: List<CustomContext>,
    onAdd: (String) -> Unit,
    onRename: (key: String, label: String) -> Unit,
    onRemove: (key: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newLabel by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<CustomContext?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text("Context labels") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Built-in: Field work, Just wondering, Lingping, Kru Boom, Kru Ae.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                if (labels.isEmpty()) {
                    Text("No own labels yet.", style = MaterialTheme.typography.bodyMedium)
                }
                labels.forEach { c ->
                    // Tap a label to rename or delete it.
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { selected = c },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(c.label, Modifier.weight(1f).padding(vertical = 12.dp), style = MaterialTheme.typography.bodyLarge)
                        Icon(Icons.Filled.Edit, contentDescription = "Rename or delete")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        newLabel, { newLabel = it }, singleLine = true,
                        label = { Text("New label") },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        enabled = newLabel.isNotBlank(),
                        onClick = { onAdd(newLabel); newLabel = "" },
                    ) { Text("Add") }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Delete: the label is deleted, or only hidden if words use it (they keep it).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )

    selected?.let { c ->
        LabelActionDialog(
            label = c.label,
            onRename = { onRename(c.key, it); selected = null },
            onDelete = { onRemove(c.key); selected = null },
            onDismiss = { selected = null },
        )
    }
}

/** Rename or delete one of the user's own labels. */
@Composable
fun LabelActionDialog(
    label: String,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(label) }
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Label") },
        text = {
            Column {
                OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                Text(
                    "Delete: the label is deleted, or only hidden if words use it (they keep it).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank() && text.trim() != label, onClick = { onRename(text) }) { Text("Rename") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { if (confirmDelete) onDelete() else confirmDelete = true }) {
                    Text(if (confirmDelete) "Really delete?" else "Delete", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
