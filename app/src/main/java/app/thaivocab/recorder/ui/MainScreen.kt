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

    var editing by remember { mutableStateOf<VocabEntry?>(null) }
    var typing by remember { mutableStateOf(false) }
    var showSetup by remember { mutableStateOf(false) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            SyncBar(
                connected = supabase.connected,
                waiting = entries.count { it.dirty },
                error = supabase.lastError,
                onClick = { showSetup = true },
            )
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
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(entries, key = { it.id }) { entry ->
                            EntryRow(entry, onPlay = { vm.play(entry) }, onClick = { editing = entry })
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        }
                    }
                }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 28.dp),
                contentAlignment = Alignment.Center,
            ) {
                RecordButton(
                    recording = recording,
                    onStart = vm::startRecording,
                    onStop = vm::stopRecording,
                    onPermissionDenied = { vm.showMessage("Microphone permission is needed") },
                )
                IconButton(
                    onClick = { typing = true },
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 24.dp).size(56.dp),
                ) {
                    Icon(Icons.Filled.Keyboard, contentDescription = "Type a word", Modifier.size(32.dp))
                }
                message?.let {
                    Text(
                        it,
                        Modifier.align(Alignment.TopCenter),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }

    editing?.let { entry ->
        EditDialog(
            entry = entry,
            onDismiss = { editing = null },
            onSave = { thai, notes -> vm.saveEdit(entry, thai, notes); editing = null },
            onDelete = { vm.delete(entry); editing = null },
            onPlay = { vm.play(entry) },
        )
    }
    if (typing) {
        TypeDialog(onDismiss = { typing = false }, onSave = { thai, notes -> vm.addTyped(thai, notes); typing = false })
    }
    if (showSetup) {
        SetupDialog(vm = vm, state = supabase, onDismiss = { showSetup = false })
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
private fun EntryRow(entry: VocabEntry, onPlay: () -> Unit, onClick: () -> Unit) {
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
                    Text("Recognising…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> Text("(no text – tap to type)", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    timeFormat.format(Date(entry.capturedAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (entry.dirty) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Filled.Cloud, contentDescription = "Waiting to sync", Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
        if (entry.audioFile != null) {
            IconButton(onClick = onPlay, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Play", Modifier.size(32.dp))
            }
        }
    }
}

/** The main control: press and hold to record, release to save. */
@Composable
private fun RecordButton(
    recording: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPermissionDenied: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val currentOnStart by rememberUpdatedState(onStart)
    val currentOnStop by rememberUpdatedState(onStop)

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) onPermissionDenied()
    }

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

    val color = if (recording) Color(0xFFB71C1C) else Color(0xFFE53935)
    Box(
        Modifier
            .size(150.dp)
            .scale(if (recording) scale else 1f)
            .clip(CircleShape)
            .background(color)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
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
