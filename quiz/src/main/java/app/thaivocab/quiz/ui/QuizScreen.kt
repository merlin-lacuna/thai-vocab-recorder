package app.thaivocab.quiz.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.thaivocab.quiz.LabelOption
import app.thaivocab.quiz.Question
import kotlinx.coroutines.delay
import java.util.Locale

private val Navy = Color(0xFF0B2545)
private val Highlight = Color(0xFFFFE27A)
private val Green = Color(0xFF2E7D32)
private val Red = Color(0xFFC62828)

@Composable
fun QuizScreen(vm: QuizActions) {
    val screen by vm.screen.collectAsState()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val s = screen) {
                Screen.Login -> LoginView(vm)
                Screen.Home -> HomeView(vm)
                is Screen.Loading -> LoadingView(s.number)
                is Screen.Asking -> AskingView(vm, s)
                is Screen.Solved -> SolvedView(vm, s)
                is Screen.Summary -> SummaryView(vm, s)
                is Screen.Failed -> {
                    Text(s.message, color = Red)
                    Button(onClick = vm::home) { Text("Back") }
                }
            }
        }
    }
}

@Composable
private fun LoginView(vm: QuizActions) {
    var email by remember { mutableStateOf(vm.email) }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Text("Thai Quiz", style = MaterialTheme.typography.headlineMedium)
    Text("Log in with the same account as the vocabulary app.")
    OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
    OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
    error?.let { Text(it, color = Red) }
    Button(enabled = !busy && email.isNotBlank() && password.isNotEmpty(), onClick = {
        busy = true; error = null
        vm.signIn(email, password) { busy = false; error = it }
    }) { Text(if (busy) "Logging in…" else "Log in") }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeView(vm: QuizActions) {
    val labels by vm.labels.collectAsState()
    val selected by vm.selected.collectAsState()
    val pushReady by vm.pushReady.collectAsState()
    val update by vm.update.collectAsState()
    val progress by vm.updateProgress.collectAsState()

    Text("Thai Quiz", style = MaterialTheme.typography.headlineMedium)
    update?.let {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(12.dp)) {
                Text("New version ${it.versionName}")
                progress?.let { p -> LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth()) }
                    ?: Button(onClick = vm::installUpdate) { Text("Install update") }
            }
        }
    }

    Text("Words from", style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = selected.isEmpty(), onClick = vm::clearLabels, label = { Text("All words") })
        labels.forEach { l: LabelOption ->
            FilterChip(selected = l.value in selected, onClick = { vm.toggleLabel(l.value) }, label = { Text(l.name) })
        }
    }
    Button(onClick = { vm.startRound() }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Text("Start ⏱", fontSize = 18.sp)
    }
    Text("One question at a time. The timer starts when each question opens.", style = MaterialTheme.typography.bodySmall)

    if (!pushReady) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Text(
                "Notifications need the ntfy app. Install ntfy from Google Play, set your server in it, " +
                    "then open this app again.",
                Modifier.padding(12.dp),
            )
        }
    }
    Spacer(Modifier.height(24.dp))
    TextButton(onClick = vm::signOut) { Text("Log out (${vm.email})") }
}

@Composable
private fun LoadingView(number: Int) {
    Text("Question $number", style = MaterialTheme.typography.titleMedium)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator()
        Text("Preparing the question… The first one can take up to 30 seconds.")
    }
}

@Composable
private fun Sentence(q: Question) {
    val i = q.sentenceEn.lowercase().indexOf(q.highlight.lowercase())
    val text = buildAnnotatedString {
        if (i < 0) append(q.sentenceEn) else {
            append(q.sentenceEn.substring(0, i))
            withStyle(SpanStyle(background = Highlight, color = Navy, fontWeight = FontWeight.Bold)) {
                append(q.sentenceEn.substring(i, i + q.highlight.length))
            }
            append(q.sentenceEn.substring(i + q.highlight.length))
        }
    }
    Card(colors = CardDefaults.cardColors(containerColor = Navy)) {
        Column(Modifier.padding(16.dp).fillMaxWidth()) {
            Text("Which sentence means:", color = Color.White.copy(alpha = 0.8f))
            Text(text, color = Color.White, fontSize = 22.sp, lineHeight = 30.sp)
        }
    }
}

@Composable
private fun AskingView(vm: QuizActions, s: Screen.Asking) {
    var ms by remember(s.question.id) { mutableLongStateOf(vm.elapsedMs()) }
    LaunchedEffect(s.question.id, s.checking) {
        while (!s.checking) { ms = vm.elapsedMs(); delay(100) }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Question ${s.number}", style = MaterialTheme.typography.titleMedium)
        Text(String.format(Locale.US, "%.1f s", ms / 1000.0), style = MaterialTheme.typography.titleMedium)
    }
    Sentence(s.question)
    if (s.wrong.isNotEmpty()) Text("ยังไม่ถูก – try again", color = Red, fontWeight = FontWeight.Bold)
    s.question.options.forEachIndexed { i, thai ->
        val wrong = s.wrong[i]
        OutlinedButton(
            onClick = { vm.choose(i) },
            enabled = wrong == null && !s.checking,
            border = BorderStroke(if (wrong != null) 2.dp else 1.dp, if (wrong != null) Red else MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(thai, fontSize = 20.sp, lineHeight = 28.sp)
                if (wrong != null) Text(wrong.english, color = Red, fontSize = 14.sp)
            }
        }
    }
    TextButton(onClick = vm::finish) { Text("Finish") }
}

@Composable
private fun SolvedView(vm: QuizActions, s: Screen.Solved) {
    val a = s.answer
    Text("ถูกต้อง!", color = Green, fontSize = 32.sp, fontWeight = FontWeight.Bold)
    Text(
        String.format(Locale.US, "Answered in %.1f s", s.seconds) +
            if (s.tries > 1) " · ${s.tries} tries" else " · first try",
    )
    Card {
        Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(a.word.thai, fontSize = 30.sp)
            Text(a.word.romanization, fontSize = 18.sp)
            Text(a.word.english + (a.word.partOfSpeech?.let { "  ($it)" } ?: ""))
            if (a.nativeAudioUrl != null) {
                OutlinedButton(onClick = { vm.playNative(a.nativeAudioUrl) }) { Text("▶ Native recording") }
            } else {
                Text("No native recording yet", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    Sentence(s.question)
    a.options.firstOrNull { it.correct }?.let { o ->
        Text(o.thai, fontSize = 20.sp, color = Green)
        Text(o.romanization, style = MaterialTheme.typography.bodyMedium)
    }
    Button(onClick = vm::nextQuestion, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Text("Next question", fontSize = 18.sp)
    }
    OutlinedButton(onClick = vm::finish, modifier = Modifier.fillMaxWidth()) { Text("Finish") }
    HorizontalDivider()
    Text("The other sentences", style = MaterialTheme.typography.titleMedium)
    a.options.filter { !it.correct }.forEach { o ->
        Column {
            Text(o.thai, fontSize = 18.sp)
            Text(o.romanization, style = MaterialTheme.typography.bodySmall)
            Text(o.english, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SummaryView(vm: QuizActions, s: Screen.Summary) {
    val first = s.results.count { it.tries == 1 }
    Text("Finished", style = MaterialTheme.typography.headlineMedium)
    Text("$first of ${s.results.size} right on the first try", fontSize = 18.sp)
    s.results.forEach { r ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.thai, fontSize = 20.sp)
                Text(r.english, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                String.format(Locale.US, "%.1f s", r.seconds) + if (r.tries == 1) "  ✓" else "  ${r.tries} tries",
                color = if (r.tries == 1) Green else Red, textAlign = TextAlign.End,
            )
        }
    }
    Button(onClick = { vm.startRound() }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Text("Start again ⏱", fontSize = 18.sp)
    }
    TextButton(onClick = vm::home) { Text("Done") }
}
