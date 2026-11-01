package app.thaivocab.recorder.sync

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Supabase connection details and the current login session, kept in SharedPreferences. */
class SupabaseConfig private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("supabase", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<State> = _state.asStateFlow()

    data class State(
        val url: String,
        val apiKey: String,
        val email: String,
        val userId: String?,
        val refreshToken: String?,
        val lastError: String?,
    ) {
        val connected: Boolean get() = userId != null && refreshToken != null
    }

    private fun read() = State(
        url = prefs.getString("url", null)?.ifBlank { null } ?: DEFAULT_URL,
        apiKey = prefs.getString("api_key", null)?.ifBlank { null } ?: DEFAULT_API_KEY,
        email = prefs.getString("email", "") ?: "",
        userId = prefs.getString("user_id", null),
        refreshToken = prefs.getString("refresh_token", null),
        lastError = prefs.getString("last_error", null),
    )

    @Synchronized
    fun save(block: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).commit()
        _state.value = read()
    }

    fun setError(message: String?) = save { putString("last_error", message) }

    /** Saves the set-up form while the user types, so nothing is lost if the window closes. */
    fun saveDraft(url: String, apiKey: String, email: String) {
        prefs.edit().putString("url", url).putString("api_key", apiKey).putString("email", email).apply()
        _state.value = read() // SharedPreferences updates its memory copy at once
    }

    companion object {
        /** The project of this app. Can be changed in the set-up window. */
        const val DEFAULT_URL = "https://vazojhdllcilzbdtavww.supabase.co"

        /**
         * Publishable key of the project. Supabase designs this key to be public: the
         * Row Level Security rules protect the data, and every app user must sign in.
         */
        const val DEFAULT_API_KEY = "sb_publishable_zQgiMRTpOkwrAMgFVb-EgA_wf8EFdSt"

        @Volatile private var instance: SupabaseConfig? = null
        fun get(context: Context): SupabaseConfig =
            instance ?: synchronized(this) { instance ?: SupabaseConfig(context).also { instance = it } }
    }
}

class HttpException(val code: Int, body: String) : IOException("HTTP $code: ${body.take(300)}")

/**
 * Minimal Supabase client: password login, token refresh, file upload to Storage,
 * and upsert into a table through the REST API (PostgREST).
 * Plain HttpURLConnection is used so the app has no extra network library.
 */
class SupabaseApi(private val baseUrl: String, private val apiKey: String) {

    data class Session(val accessToken: String, val refreshToken: String, val userId: String)

    fun signIn(email: String, password: String): Session =
        authRequest("password", JSONObject().put("email", email).put("password", password))

    fun refresh(refreshToken: String): Session =
        authRequest("refresh_token", JSONObject().put("refresh_token", refreshToken))

    private fun authRequest(grant: String, body: JSONObject): Session {
        val json = JSONObject(
            request("POST", "/auth/v1/token?grant_type=$grant", null, "application/json", body.toString().toByteArray())
        )
        return Session(
            accessToken = json.getString("access_token"),
            refreshToken = json.getString("refresh_token"),
            userId = json.getJSONObject("user").getString("id"),
        )
    }

    fun uploadFile(token: String, bucket: String, path: String, file: File, contentType: String) {
        request(
            "POST", "/storage/v1/object/$bucket/${path.split('/').joinToString("/") { enc(it) }}", token, contentType,
            file.readBytes(), mapOf("x-upsert" to "true"),
        )
    }

    fun upsert(token: String, table: String, row: JSONObject) {
        request(
            "POST", "/rest/v1/$table?on_conflict=id", token, "application/json",
            row.toString().toByteArray(),
            mapOf("Prefer" to "resolution=merge-duplicates,return=minimal"),
        )
    }

    /** Calls the "transcribe" Edge Function, which returns {"text": "..."} for a stored clip. */
    fun transcribe(token: String, audioPath: String): String {
        val body = JSONObject().put("audio_path", audioPath).toString().toByteArray()
        val json = JSONObject(request("POST", "/functions/v1/transcribe", token, "application/json", body))
        return json.optString("text", "")
    }

    /** Reads rows through the REST API. [query] is the PostgREST query string. */
    fun select(token: String, table: String, query: String): JSONArray =
        JSONArray(String(requestBytes("GET", "/rest/v1/$table?$query", token, null, null)))

    /** Downloads a private file from Storage. */
    fun downloadFile(token: String, bucket: String, path: String): ByteArray =
        requestBytes(
            "GET", "/storage/v1/object/authenticated/$bucket/${path.split('/').joinToString("/") { enc(it) }}",
            token, null, null,
        )

    private fun request(
        method: String,
        path: String,
        token: String?,
        contentType: String,
        body: ByteArray,
        extraHeaders: Map<String, String> = emptyMap(),
    ): String = String(requestBytes(method, path, token, contentType, body, extraHeaders))

    private fun requestBytes(
        method: String,
        path: String,
        token: String?,
        contentType: String?,
        body: ByteArray?,
        extraHeaders: Map<String, String> = emptyMap(),
    ): ByteArray {
        val conn = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("apikey", apiKey)
            conn.setRequestProperty("Authorization", "Bearer ${token ?: apiKey}")
            contentType?.let { conn.setRequestProperty("Content-Type", it) }
            extraHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val bytes = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.use { it.readBytes() } ?: ByteArray(0)
            if (code !in 200..299) throw HttpException(code, String(bytes))
            return bytes
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
