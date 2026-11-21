package app.thaivocab.quiz

import android.content.Context
import app.thaivocab.core.data.CaptureContext
import app.thaivocab.core.sync.HttpException
import app.thaivocab.core.sync.SupabaseApi
import app.thaivocab.core.sync.SupabaseConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Question(val id: String, val sentenceEn: String, val highlight: String, val options: List<String>)

data class Option(val thai: String, val romanization: String, val english: String, val correct: Boolean)

data class Word(val thai: String, val romanization: String, val english: String, val partOfSpeech: String?)

data class Answer(
    val correct: Boolean,
    val choice: Int,
    val word: Word,
    val nativeAudioUrl: String?,
    val options: List<Option>,
)

/** A label in the filter. [value] is what the server matches: a label key or a teacher name. */
data class LabelOption(val value: String, val name: String)

/**
 * Calls the Edge Function "daily-quiz" and keeps the login. The refresh token is saved in
 * the same settings as in the other apps (SupabaseConfig); the short-lived access token is
 * kept in memory only.
 */
class QuizApi private constructor(context: Context) {
    private val config = SupabaseConfig.get(context)
    private val prefs = context.applicationContext.getSharedPreferences("quiz", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private var accessToken: String? = null
    private var accessUntil = 0L

    private val api get() = config.state.value.let { SupabaseApi(it.url, it.apiKey) }

    /** Returns an error message, or null after a successful login. */
    suspend fun signIn(email: String, password: String): String? = withContext(Dispatchers.IO) {
        try {
            val s = api.signIn(email.trim(), password)
            lock.withLock { keep(s) }
            config.save {
                putString("email", email.trim())
                putString("user_id", s.userId)
                putString("refresh_token", s.refreshToken)
                remove("last_error")
            }
            null
        } catch (e: HttpException) {
            if (e.code == 400) "Wrong email or password." else e.message
        } catch (e: java.io.IOException) {
            "No internet connection."
        }
    }

    fun signOut() {
        accessToken = null
        config.save { remove("refresh_token"); remove("user_id") }
    }

    private fun keep(s: SupabaseApi.Session) {
        accessToken = s.accessToken
        accessUntil = System.currentTimeMillis() + 45 * 60_000 // tokens last 60 minutes
        config.save { putString("refresh_token", s.refreshToken) }
    }

    private suspend fun token(): String = lock.withLock {
        accessToken?.takeIf { System.currentTimeMillis() < accessUntil }?.let { return it }
        val refresh = config.state.value.refreshToken ?: throw NotLoggedIn()
        val s = try {
            api.refresh(refresh)
        } catch (e: HttpException) {
            if (e.code in 400..401) { signOut(); throw NotLoggedIn() }
            throw e
        }
        keep(s)
        s.accessToken
    }

    /** Built-in labels plus the user's own visible labels from the table capture_contexts. */
    suspend fun labels(): List<LabelOption> = withContext(Dispatchers.IO) {
        val builtIn = CaptureContext.BUILT_IN.map { LabelOption(it.teacher ?: it.key, it.label) }
        val rows = api.select(token(), "capture_contexts", "select=key,label,hidden,sort_order&hidden=eq.false&order=sort_order,label")
        builtIn + List(rows.length()) { i ->
            val o = rows.getJSONObject(i)
            LabelOption(o.getString("key"), o.getString("label"))
        }
    }

    suspend fun practice(labels: List<String>, exclude: List<String>): Question = withContext(Dispatchers.IO) {
        val json = call("GET", "action=practice&labels=${enc(labels.joinToString(","))}&exclude=${enc(exclude.joinToString(","))}", auth = true)
        question(json, json.getString("id"))
    }

    /** A quiz by id, for example the one from a notification. */
    suspend fun question(id: String): Question = withContext(Dispatchers.IO) {
        question(call("GET", "q=${enc(id)}", auth = false), id)
    }

    suspend fun answer(id: String, choice: Int, ms: Long): Answer = withContext(Dispatchers.IO) {
        val j = call("GET", "q=${enc(id)}&c=$choice&ms=$ms&src=app", auth = false)
        val w = j.getJSONObject("word")
        val opts = j.getJSONArray("options")
        Answer(
            correct = j.getBoolean("correct"),
            choice = j.getInt("choice"),
            word = Word(w.optString("thai"), w.optString("romanization"), w.optString("english"),
                w.optString("part_of_speech").ifBlank { null }?.takeIf { it != "null" }),
            nativeAudioUrl = j.optString("native_audio_url").ifBlank { null }?.takeIf { it != "null" },
            options = List(opts.length()) { i ->
                val o = opts.getJSONObject(i)
                Option(o.optString("thai"), o.optString("romanization"), o.optString("english"), o.optBoolean("correct"))
            },
        )
    }

    suspend fun registerPush(endpoint: String) = withContext(Dispatchers.IO) {
        call("POST", "action=push", auth = true, body = JSONObject().put("endpoint", endpoint))
    }

    /** The UnifiedPush address of this phone, or null while the app has none. */
    val endpoint: String? get() = prefs.getString("endpoint", null)
    fun saveEndpoint(url: String?) = prefs.edit().putString("endpoint", url).apply()

    var selectedLabels: List<String>
        get() = prefs.getString("labels", "")!!.split(",").filter { it.isNotBlank() }
        set(v) = prefs.edit().putString("labels", v.joinToString(",")).apply()

    private fun question(j: JSONObject, id: String): Question {
        val opts = j.getJSONArray("options")
        return Question(id, j.getString("sentence_en"), j.getString("highlight"),
            List(opts.length()) { opts.getJSONObject(it).getString("thai") })
    }

    private suspend fun call(method: String, query: String, auth: Boolean, body: JSONObject? = null): JSONObject {
        val bearer = if (auth) token() else null
        val conn = URL("${config.state.value.url}/functions/v1/daily-quiz?$query").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 150_000 // a new quiz can take a minute when none is prepared
            bearer?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                val bytes = body.toString().toByteArray()
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            val text = ((if (code in 200..299) conn.inputStream else conn.errorStream)?.use { String(it.readBytes()) }) ?: ""
            if (code == 401 && auth) { accessToken = null }
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).getString("error") }.getOrDefault("HTTP $code")
                throw ServerError(code, msg)
            }
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    class NotLoggedIn : Exception("Please log in again.")
    class ServerError(val code: Int, message: String) : Exception(message)

    companion object {
        @Volatile private var instance: QuizApi? = null
        fun get(context: Context): QuizApi =
            instance ?: synchronized(this) { instance ?: QuizApi(context.applicationContext).also { instance = it } }
    }
}
