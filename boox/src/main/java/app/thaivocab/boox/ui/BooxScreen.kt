package app.thaivocab.boox.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.thaivocab.boox.ink.InkView
import app.thaivocab.core.data.CaptureContext
import app.thaivocab.core.data.Transcription
import app.thaivocab.core.data.VocabEntry
import app.thaivocab.core.ui.LabelActionDialog
import app.thaivocab.core.ui.LabelsDialog
import app.thaivocab.core.ui.SetupDialog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Part of the screen height that the writing box takes. */
private const val PAD_HEIGHT = 0.39f

@Composable
fun BooxScreen(vm: BooxViewModel = viewModel()) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val supabase by vm.supabase.collectAsStateWithLifecycle()
    val captureContext by vm.captureContext.collectAsStateWithLifecycle()
    val contextOptions by vm.contextOptions.collectAsStateWithLifecycle()
    val customContexts by vm.customContexts.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val updateProgress by vm.updateProgress.collectAsStateWithLifecycle()

    var editing by remember { mutableStateOf<VocabEntry?>(null) }
    var typing by remember { mutableStateOf(false) }
    var showSetup by remember { mutableStateOf(false) }
    var showLabels by remember { mutableStateOf(false) }
    var labelMenu by remember { mutableStateOf<CaptureContext?>(null) }

    var inkView by remember { mutableStateOf<InkView?>(null) }
    var thaiInk by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        vm.recheckContext()
        vm.syncNow() // also downloads changes made on the phone or in the Supabase dashboard
        vm.checkForUpdate()
        onPauseOrDispose { }
    }

    // While a window covers the writing box, the pen library must not draw: the ink would
    // appear on top of the window.
    val windowOpen = editing != null || typing || showSetup || showLabels || labelMenu != null
    LaunchedEffect(windowOpen, inkView) { inkView?.setActive(!windowOpen) }

    BooxContent(
        entries = entries,
        connected = supabase.connected,
        syncError = supabase.lastError,
        captureContext = captureContext,
        contextOptions = contextOptions,
        onContextChange = vm::setContext,
        onEditLabels = { showLabels = true },
        onLabelMenu = { labelMenu = it },
        updateBuild = update?.versionCode,
        updateProgress = updateProgress,
        onUpdateClick = vm::installUpdate,
        onSyncBarClick = { showSetup = true },
        onEntryClick = { editing = it },
        thaiInk = thaiInk,
        onClear = { inkView?.clear() },
        onType = { typing = true },
        onNext = { phonetic ->
            inkView?.let { view ->
                val saved = vm.saveInk(
                    view.snapshot(), view.width.toFloat(), view.height.toFloat(), view.strokeWidth, phonetic,
                )
                if (saved) view.clear()
            }
        },
        pad = {
            AndroidView(
                factory = { ctx -> InkView(ctx) { thaiInk = it }.also { inkView = it } },
                modifier = Modifier.fillMaxSize(),
            )
        },
    )

    editing?.let { entry ->
        EditWindow(
            entry = entry,
            contextOptions = contextOptions,
            onDismiss = { editing = null },
            onSave = { thai, notes, context -> vm.saveEdit(entry, thai, notes, context); editing = null },
            onDelete = { vm.delete(entry); editing = null },
            onReadAgain = { vm.readAgainAsOtherType(entry); editing = null },
            onRetry = { vm.retryReading(entry); editing = null },
            onRemoveImage = { vm.removeImage(entry); editing = null },
        )
    }
    if (typing) {
        TypeWindow(onDismiss = { typing = false }, onSave = { thai, notes -> vm.addTyped(thai, notes); typing = false })
    }
    labelMenu?.let { c ->
        LabelActionDialog(
            label = c.label,
            onRename = { vm.renameContext(c.key, it); labelMenu = null },
            onDelete = { vm.removeContext(c.key); labelMenu = null },
            onDismiss = { labelMenu = null },
        )
    }
    if (showLabels) {
        LabelsDialog(
            labels = customContexts.filter { !it.hidden },
            onAdd = vm::addContext,
            onRename = vm::renameContext,
            onRemove = vm::removeContext,
            onDismiss = { showLabels = false },
        )
    }
    if (showSetup) {
        SetupDialog(connection = vm.connection, state = supabase, onDismiss = { showSetup = false })
    }
}

/**
 * The visible screen, without any app logic. Previews and screenshot tests call this
 * function directly with sample data and a plain box as [pad].
 */
@Composable
fun BooxContent(
    entries: List<VocabEntry>,
    connected: Boolean,
    syncError: String?,
    captureContext: CaptureContext,
    contextOptions: List<CaptureContext>,
    onContextChange: (CaptureContext) -> Unit,
    onEditLabels: () -> Unit,
    onLabelMenu: (CaptureContext) -> Unit,
    updateBuild: Long?,
    updateProgress: Float?,
    onUpdateClick: () -> Unit,
    onSyncBarClick: () -> Unit,
    onEntryClick: (VocabEntry) -> Unit,
    thaiInk: Boolean,
    onClear: () -> Unit,
    onType: () -> Unit,
    onNext: (phonetic: Boolean) -> Unit,
    pad: @Composable () -> Unit,
) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Paper)
            .safeDrawingPadding()
    ) {
        val padHeight = maxHeight * PAD_HEIGHT
        Column(Modifier.fillMaxSize()) {
            if (updateBuild != null) UpdateBanner(updateBuild, updateProgress, onUpdateClick)
            SyncBar(connected, entries.count { it.dirty }, syncError, onSyncBarClick)
            HorizontalDivider(color = Ink, thickness = 2.dp)

            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(entries, key = { it.id }) { entry ->
                    EntryRow(entry, onClick = { onEntryClick(entry) })
                    HorizontalDivider(color = Grey, thickness = 1.dp)
                }
            }

            HorizontalDivider(color = Ink, thickness = 2.dp)
            ContextBar(contextOptions, captureContext, onContextChange, onEditLabels, onLabelMenu)

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(padHeight)
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .border(2.dp, Ink)
                    .padding(2.dp)
            ) { pad() }

            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val shape = RoundedCornerShape(6.dp)
                OutlinedButton(onClick = onClear, shape = shape, border = BorderStroke(2.dp, Ink), modifier = Modifier.height(60.dp)) {
                    Text("Clear", fontSize = 20.sp)
                }
                OutlinedButton(onClick = onType, shape = shape, border = BorderStroke(2.dp, Ink), modifier = Modifier.height(60.dp)) {
                    Icon(Icons.Filled.Keyboard, contentDescription = "Type a word", Modifier.size(28.dp))
                }
                Button(onClick = { onNext(true) }, enabled = thaiInk, shape = shape, modifier = Modifier.weight(1f).height(60.dp)) {
                    Text("Next: Phonetic", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                }
                Button(onClick = { onNext(false) }, enabled = thaiInk, shape = shape, modifier = Modifier.weight(1f).height(60.dp)) {
                    Text("Next: ไทย", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** Shown when GitHub has a newer build. One tap downloads it and opens the installer. */
@Composable
private fun UpdateBanner(build: Long, progress: Float?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Ink)
            .clickable(enabled = progress == null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.SystemUpdate, contentDescription = null, tint = Paper)
        Spacer(Modifier.width(10.dp))
        Text(
            if (progress == null) "New version (build $build) – tap to install"
            else "Downloading update… ${(progress * 100).toInt()} %",
            color = Paper, fontSize = 18.sp,
        )
    }
}

@Composable
private fun SyncBar(connected: Boolean, waiting: Int, error: String?, onClick: () -> Unit) {
    val (icon, text) = when {
        !connected -> Icons.Filled.CloudOff to "Not connected to database – tap to set up"
        error != null && waiting > 0 -> Icons.Filled.CloudUpload to "$waiting waiting – sync problem (tap)"
        waiting > 0 -> Icons.Filled.CloudUpload to "$waiting waiting to sync"
        else -> Icons.Filled.CloudDone to "All saved to database"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Ink)
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 18.sp, color = Ink)
    }
}

/** The same labels as on the phone: built-in ones and the user's own. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContextBar(
    options: List<CaptureContext>,
    selected: CaptureContext,
    onSelect: (CaptureContext) -> Unit,
    onEditLabels: () -> Unit,
    onLabelMenu: (CaptureContext) -> Unit,
) {
    FlowRow(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { c ->
            FilterChip(
                selected = c.id == selected.id,
                // Tapping your own label when it is already selected opens Rename / Delete.
                onClick = { if (c.id == selected.id && !c.builtIn) onLabelMenu(c) else onSelect(c) },
                label = { Text(c.label, fontSize = 17.sp) },
                leadingIcon = if (c.isLesson) {
                    { Icon(Icons.Filled.School, contentDescription = null, Modifier.size(FilterChipDefaults.IconSize)) }
                } else null,
                border = BorderStroke(1.5.dp, Ink),
            )
        }
        AssistChip(
            onClick = onEditLabels,
            label = { Text("Labels", fontSize = 17.sp) },
            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null, Modifier.size(FilterChipDefaults.IconSize)) },
            border = BorderStroke(1.5.dp, Ink),
        )
    }
}

private val timeFormat = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())

/** The handwriting image of an entry, or an empty box of the same size. */
@Composable
fun HandwritingImage(path: String?, modifier: Modifier = Modifier) {
    // The file can arrive later (download from the server), so the length is part of the key.
    val length = path?.let { File(it).length() } ?: 0L
    val bitmap = remember(path, length) {
        if (path != null && length > 0) runCatching { BitmapFactory.decodeFile(path) }.getOrNull() else null
    }
    Box(modifier.border(1.dp, Grey), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), contentDescription = "Your handwriting",
                contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp))
        }
    }
}

@Composable
private fun EntryRow(entry: VocabEntry, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HandwritingImage(entry.imageFile, Modifier.width(220.dp).height(96.dp))
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            val thai = entry.thai
            when {
                thai != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(thai, fontSize = 30.sp, fontWeight = FontWeight.Medium, color = Ink)
                    if (entry.transcription == Transcription.GUESSED) {
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "GUESSED", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Ink,
                            modifier = Modifier.border(1.5.dp, Ink).padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
                entry.transcription == Transcription.PENDING ->
                    Text("Waiting for Thai text…", fontSize = 20.sp, fontStyle = FontStyle.Italic, color = Grey)
                entry.transcription == Transcription.FAILED ->
                    Text("Could not read – tap to type or retry", fontSize = 20.sp, fontStyle = FontStyle.Italic, color = Grey)
                else -> Text("(no text – tap to type)", fontSize = 20.sp, fontStyle = FontStyle.Italic, color = Grey)
            }
            if (thai != null) {
                val meaning = listOfNotNull(entry.romanization, entry.english).joinToString(" · ")
                if (meaning.isNotEmpty()) Text(meaning, fontSize = 17.sp, color = Ink)
                else Text("Getting meaning…", fontSize = 17.sp, fontStyle = FontStyle.Italic, color = Grey)
            }
            if (entry.writtenScript == "phonetic" && entry.writtenText != null) {
                Text("You wrote: ${entry.writtenText}", fontSize = 16.sp, color = Grey)
            }
            entry.writtenEnglish?.let { Text("Your translation: $it", fontSize = 16.sp, color = Grey) }
            Text(
                timeFormat.format(Date(entry.capturedAt)) + " · " + entry.context.label +
                    (entry.notes?.let { " · $it" } ?: ""),
                fontSize = 14.sp, color = Grey,
            )
        }
    }
}
