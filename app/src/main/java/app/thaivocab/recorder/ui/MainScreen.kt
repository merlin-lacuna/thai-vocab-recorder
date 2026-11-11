package app.thaivocab.recorder.ui

import android.Manifest
import android.content.pm.PackageManager
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AssistChip
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import app.thaivocab.recorder.data.CaptureContext
import app.thaivocab.recorder.data.Transcription
import app.thaivocab.recorder.data.VocabEntry
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val recording by vm.recording.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val supabase by vm.supabase.collectAsStateWithLifecycle()
    val captureContext by vm.captureContext.collectAsStateWithLifecycle()
    val levels by vm.levels.collectAsStateWithLifecycle()
    val playingId by vm.playingId.collectAsStateWithLifecycle()
    val playProgress = vm.playProgress.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val updateProgress by vm.updateProgress.collectAsStateWithLifecycle()
    val nativeMode by vm.nativeMode.collectAsStateWithLifecycle()
    val contextOptions by vm.contextOptions.collectAsStateWithLifecycle()
    val customContexts by vm.customContexts.collectAsStateWithLifecycle()
    var showLabels by remember { mutableStateOf(false) }
    var labelMenu by remember { mutableStateOf<CaptureContext?>(null) }

    LifecycleResumeEffect(Unit) {
        vm.recheckContext()
        vm.syncNow() // also downloads changes made elsewhere, e.g. in the Supabase dashboard
        vm.checkForUpdate()
        onPauseOrDispose { }
    }

    var editing by remember { mutableStateOf<VocabEntry?>(null) }
    var typing by remember { mutableStateOf(false) }
    var showSetup by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) vm.showMessage("Microphone permission is needed")
    }

    MainContent(
        entries = entries,
        recording = recording,
        message = message,
        connected = supabase.connected,
        syncError = supabase.lastError,
        captureContext = captureContext,
        onContextChange = vm::setContext,
        contextOptions = contextOptions,
        onEditLabels = { showLabels = true },
        onLabelMenu = { labelMenu = it },
        levels = levels,
        playingId = playingId,
        playProgress = { playProgress.value },
        updateBuild = update?.versionCode,
        updateProgress = updateProgress,
        onUpdateClick = vm::installUpdate,
        nativeMode = nativeMode,
        onNativeModeChange = vm::setNativeMode,
        onSyncBarClick = { showSetup = true },
        onEntryClick = { editing = it },
        onPlay = vm::play,
        onStartRecording = vm::startRecording,
        onStopRecording = vm::stopRecording,
        hasMicPermission = {
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        },
        onRequestMicPermission = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
        onTypeClick = { typing = true },
    )

    editing?.let { entry ->
        EditDialog(
            entry = entry,
            onDismiss = { editing = null },
            onSave = { thai, notes, context -> vm.saveEdit(entry, thai, notes, context); editing = null },
            onDelete = { vm.delete(entry); editing = null },
            onPlay = { vm.play(entry) },
            onRetry = { vm.retryTranscription(entry); editing = null },
            contextOptions = contextOptions,
            onRemoveOwnRecording = { vm.removeOwnRecording(entry); editing = null },
            onRemoveNativeClip = { vm.removeNativeClip(entry); editing = null },
        )
    }
    if (typing) {
        TypeDialog(onDismiss = { typing = false }, onSave = { thai, notes -> vm.addTyped(thai, notes); typing = false })
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
        SetupDialog(vm = vm, state = supabase, onDismiss = { showSetup = false })
    }
}

/**
 * The visible screen, without any app logic. Previews and screenshot tests call
 * this function directly with sample data.
 */
@Composable
fun MainContent(
    entries: List<VocabEntry>,
    recording: Boolean,
    message: String?,
    connected: Boolean,
    syncError: String?,
    captureContext: CaptureContext = CaptureContext.FIELD,
    onContextChange: (CaptureContext) -> Unit = {},
    contextOptions: List<CaptureContext> = CaptureContext.BUILT_IN,
    onEditLabels: () -> Unit = {},
    onLabelMenu: (CaptureContext) -> Unit = {},
    levels: List<Float> = emptyList(),
    playingId: String? = null,
    playProgress: () -> Float = { 0f },
    updateBuild: Long? = null,
    updateProgress: Float? = null,
    onUpdateClick: () -> Unit = {},
    nativeMode: Boolean = false,
    onNativeModeChange: (Boolean) -> Unit = {},
    onSyncBarClick: () -> Unit = {},
    onEntryClick: (VocabEntry) -> Unit = {},
    onPlay: (VocabEntry) -> Unit = {},
    onStartRecording: () -> Unit = {},
    onStopRecording: () -> Unit = {},
    hasMicPermission: () -> Boolean = { true },
    onRequestMicPermission: () -> Unit = {},
    onTypeClick: () -> Unit = {},
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            SyncBar(
                connected = connected,
                waiting = entries.count { it.dirty },
                error = syncError,
                onClick = onSyncBarClick,
            )
            if (updateBuild != null) UpdateBanner(updateBuild, updateProgress, onUpdateClick)
            HorizontalDivider()

            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (entries.isEmpty()) {
                    Text(
                        "Hold the red button and say the word.\nThe Thai text appears here.",
                        Modifier.align(Alignment.Center).padding(32.dp),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val listState = rememberLazyListState()
                    val scope = rememberCoroutineScope()
                    // After a new recording, show the newest entry at the top.
                    LaunchedEffect(entries.firstOrNull()?.id) { listState.animateScrollToItem(0) }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScrollbar(listState, MaterialTheme.colorScheme.onSurfaceVariant),
                    ) {
                        items(entries, key = { it.id }) { entry ->
                            EntryRow(
                                entry,
                                playing = entry.id == playingId,
                                nativeMode = nativeMode,
                                playProgress = playProgress,
                                onPlay = { onPlay(entry) },
                                onClick = { onEntryClick(entry) },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        }
                    }
                    // Shown when the list is not at the top.
                    if (listState.canScrollBackward) {
                        SmallFloatingActionButton(
                            onClick = { scope.launch { listState.animateScrollToItem(0) } },
                            modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                        ) {
                            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.ArrowUpward, contentDescription = null, Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Newest")
                            }
                        }
                    }
                }
            }

            HorizontalDivider()
            // Context labels near the record button, in reach of the thumb.
            ContextBar(
                options = contextOptions,
                selected = captureContext,
                onSelect = onContextChange,
                onEditLabels = onEditLabels,
                onLabelMenu = onLabelMenu,
            )

            // Waveform while recording; otherwise space for short messages.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (recording) {
                    Waveform(levels, Modifier.fillMaxSize())
                } else {
                    message?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 28.dp),
                contentAlignment = Alignment.Center,
            ) {
                NativeToggle(
                    on = nativeMode,
                    onChange = onNativeModeChange,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp),
                )
                RecordButton(
                    recording = recording,
                    native = nativeMode,
                    onStart = onStartRecording,
                    onStop = onStopRecording,
                    hasPermission = hasMicPermission,
                    onRequestPermission = onRequestMicPermission,
                )
                IconButton(
                    onClick = onTypeClick,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 24.dp).size(56.dp),
                ) {
                    Icon(Icons.Filled.Keyboard, contentDescription = "Type a word", Modifier.size(32.dp))
                }
            }
        }
    }
}

/**
 * Thin scrollbar on the right edge of a list. It shows where the visible part is in
 * the whole list, and stays visible whenever the list is longer than the screen.
 */
private fun Modifier.verticalScrollbar(state: LazyListState, color: Color): Modifier = drawWithContent {
    drawContent()
    val info = state.layoutInfo
    val visible = info.visibleItemsInfo
    val total = info.totalItemsCount
    if (total == 0 || visible.isEmpty()) return@drawWithContent
    if (!state.canScrollForward && !state.canScrollBackward) return@drawWithContent

    // Position in "items", including the part of the first item that is scrolled away.
    val first = visible.first()
    val position = first.index + state.firstVisibleItemScrollOffset.toFloat() / first.size.coerceAtLeast(1)
    val shown = visible.size.toFloat().coerceAtMost(total.toFloat())
    val thumbHeight = (size.height * shown / total).coerceAtLeast(32.dp.toPx())
    val maxPosition = (total - shown).coerceAtLeast(1f)
    val top = (size.height - thumbHeight) * (position / maxPosition).coerceIn(0f, 1f)
    val width = 4.dp.toPx()
    drawRoundRect(
        color = color.copy(alpha = 0.5f),
        topLeft = Offset(size.width - width - 2.dp.toPx(), top),
        size = Size(width, thumbHeight),
        cornerRadius = CornerRadius(width / 2),
    )
}

/** Shown when GitHub has a newer build. One tap downloads it and opens the installer. */
@Composable
private fun UpdateBanner(build: Long, progress: Float?, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = progress == null, onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.SystemUpdate, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text(
                if (progress == null) "New version (build $build) – tap to install"
                else "Downloading update… ${(progress * 100).toInt()} %",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** Field work (default), Lingping club, or a lesson with a teacher. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContextBar(
    options: List<CaptureContext>,
    selected: CaptureContext,
    onSelect: (CaptureContext) -> Unit,
    onEditLabels: () -> Unit,
    onLabelMenu: (CaptureContext) -> Unit,
) {
    // Wraps onto a second line when the screen is too narrow, so every label is visible.
    FlowRow(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        options.forEach { c ->
            FilterChip(
                selected = c.id == selected.id,
                // Tapping your own label when it is already selected opens Rename / Delete.
                onClick = { if (c.id == selected.id && !c.builtIn) onLabelMenu(c) else onSelect(c) },
                label = { Text(c.label) },
                leadingIcon = if (c.isLesson) {
                    { Icon(Icons.Filled.School, contentDescription = null, Modifier.size(FilterChipDefaults.IconSize)) }
                } else null,
            )
        }
        AssistChip(
            onClick = onEditLabels,
            label = { Text("Labels") },
            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null, Modifier.size(FilterChipDefaults.IconSize)) },
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
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val timeFormat = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())

@Composable
private fun EntryRow(
    entry: VocabEntry,
    playing: Boolean,
    nativeMode: Boolean,
    playProgress: () -> Float,
    onPlay: () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            when {
                entry.thai != null -> Text(entry.thai, fontSize = 26.sp, fontWeight = FontWeight.Medium)
                entry.transcription == Transcription.PENDING -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Getting Thai text…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> Column {
                    Text(
                        "Thai not recognised – tap to type it or try again",
                        color = MaterialTheme.colorScheme.error,
                    )
                    entry.transcriptionError?.let {
                        Text(
                            "Reason: $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            val meaning = listOfNotNull(entry.romanization, entry.english).joinToString(" · ")
            if (meaning.isNotEmpty()) {
                Text(meaning, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            } else if (entry.thai != null && System.currentTimeMillis() - entry.capturedAt < 3 * 60_000) {
                // Step 2 (romanization and English) usually arrives a few seconds after the Thai text.
                Text("Getting meaning…", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (entry.alternatives.isNotEmpty()) {
                Text(
                    entry.alternatives.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            entry.notes?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    timeFormat.format(Date(entry.capturedAt)) +
                        if (entry.context.id != CaptureContext.FIELD.id) " · ${entry.context.label}" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (entry.nativeAudioFile != null) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Filled.RecordVoiceOver, contentDescription = "Has native pronunciation",
                        Modifier.size(14.dp), tint = NativeBlue,
                    )
                }
                if (entry.dirty) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Filled.Cloud, contentDescription = "Waiting to sync", Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
        if (entry.audioFile != null || entry.nativeAudioFile != null) {
            // Which clip the button plays: the switch decides, the other one is the fallback.
            val playsNative = entry.nativeAudioFile != null && (nativeMode || entry.audioFile == null)
            IconButton(onClick = onPlay, modifier = Modifier.size(56.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    if (playing) {
                        // Ring that fills while the clip plays.
                        CircularProgressIndicator(
                            progress = playProgress,
                            modifier = Modifier.size(46.dp),
                            strokeWidth = 3.dp,
                            trackColor = MaterialTheme.colorScheme.outlineVariant,
                            strokeCap = StrokeCap.Round,
                        )
                    }
                    Icon(
                        if (playing) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = if (playing) "Stop" else "Play",
                        Modifier.size(30.dp),
                        tint = when {
                            playsNative -> NativeBlue
                            playing -> MaterialTheme.colorScheme.primary
                            else -> LocalContentColor.current
                        },
                    )
                }
            }
        }
    }
}

/** Live bars for the microphone loudness, newest on the right (like the Pixel Recorder). */
@Composable
private fun Waveform(levels: List<Float>, modifier: Modifier = Modifier) {
    val color = Color(0xFFE53935)
    val quiet = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val slots = 60
        val gap = size.width / slots
        val barWidth = gap * 0.55f
        val mid = size.height / 2
        for (i in 0 until slots) {
            // Align the newest value to the right edge.
            val level = levels.getOrNull(levels.size - slots + i) ?: 0f
            // Square root makes quiet speech visible; never less than a small dot.
            val h = maxOf(barWidth, kotlin.math.sqrt(level) * size.height)
            val x = i * gap + gap / 2
            drawLine(
                color = if (level > 0.02f) color else quiet,
                start = Offset(x, mid - h / 2),
                end = Offset(x, mid + h / 2),
                strokeWidth = barWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}

private val NativeBlue = Color(0xFF1E88E5)

/** "Native speaker" switch: recordings and playback use the native speaker's clips. */
@Composable
private fun NativeToggle(on: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onChange(!on) }
            .background(if (on) NativeBlue.copy(alpha = 0.15f) else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.RecordVoiceOver, contentDescription = null, Modifier.size(28.dp),
            tint = if (on) NativeBlue else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (on) "Native\nON" else "Native\noff",
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            color = if (on) NativeBlue else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The main control: press and hold to record, release to save. */
@Composable
private fun RecordButton(
    recording: Boolean,
    native: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    hasPermission: () -> Boolean,
    onRequestPermission: () -> Unit,
) {
    val view = LocalView.current
    val currentOnStart by rememberUpdatedState(onStart)
    val currentOnStop by rememberUpdatedState(onStop)
    val currentHasPermission by rememberUpdatedState(hasPermission)
    val currentOnRequestPermission by rememberUpdatedState(onRequestPermission)

    var startedAt by remember { mutableLongStateOf(0L) }
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(recording) {
        if (recording) {
            startedAt = System.currentTimeMillis()
            while (true) {
                elapsed = System.currentTimeMillis() - startedAt
                delay(100)
            }
        }
    }

    val pulse = rememberInfiniteTransition(label = "pulse")
    val scale by pulse.animateFloat(
        initialValue = 1f, targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(450), RepeatMode.Reverse), label = "scale",
    )

    // Red for my own recordings, blue for a native speaker's recording.
    val color = when {
        native && recording -> Color(0xFF0D47A1)
        native -> NativeBlue
        recording -> Color(0xFFB71C1C)
        else -> Color(0xFFE53935)
    }
    Box(
        Modifier
            .size(150.dp)
            .scale(if (recording) scale else 1f)
            .clip(CircleShape)
            .background(color)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    if (!currentHasPermission()) {
                        currentOnRequestPermission()
                        return@detectTapGestures
                    }
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    currentOnStart()
                    tryAwaitRelease()
                    currentOnStop()
                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Filled.Mic, contentDescription = "Hold to record", Modifier.size(56.dp), tint = Color.White)
            Spacer(Modifier.height(4.dp))
            Text(
                if (recording) "%.1f s".format(elapsed / 1000.0) else "Hold",
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
