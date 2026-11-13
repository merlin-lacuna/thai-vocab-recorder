package app.thaivocab.core.sync

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Sign-in and sign-out for Supabase. Used by the connection window of both apps. */
class Connection(context: Context) {
    private val app = context.applicationContext
    private val config = SupabaseConfig.get(app)

    /** Password typed in the set-up window. Kept in memory only, never written to storage. */
    var draftPassword: String = ""

    /** Signs in to Supabase. Returns an error message, or null on success. */
    suspend fun connect(url: String, apiKey: String, email: String, password: String): String? =
        withContext(Dispatchers.IO) {
            val cleanUrl = url.trim().trimEnd('/')
            try {
                val session = SupabaseApi(cleanUrl, apiKey.trim()).signIn(email.trim(), password)
                config.save {
                    putString("url", cleanUrl)
                    putString("api_key", apiKey.trim())
                    putString("email", email.trim())
                    putString("user_id", session.userId)
                    putString("refresh_token", session.refreshToken)
                    remove("last_error")
                }
                draftPassword = ""
                SyncWorker.schedule(app)
                null
            } catch (e: UnknownHostException) {
                // Android reports "Unable to resolve host" when the device is offline.
                NO_INTERNET
            } catch (e: ConnectException) {
                NO_INTERNET
            } catch (e: SocketTimeoutException) {
                NO_INTERNET
            } catch (e: Exception) {
                e.message ?: e.toString()
            }
        }

    fun saveSetupDraft(url: String, apiKey: String, email: String) = config.saveDraft(url, apiKey, email)

    fun disconnect() = config.save { remove("refresh_token"); remove("user_id") }

    fun syncNow() = SyncWorker.schedule(app)

    private companion object {
        const val NO_INTERNET = "No internet connection. Switch on Wi-Fi and try again."
    }
}
