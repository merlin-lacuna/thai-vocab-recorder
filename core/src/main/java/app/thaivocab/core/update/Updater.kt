package app.thaivocab.core.update

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.PackageInfoCompat
import app.thaivocab.core.AppProfile
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks the "latest" GitHub release for a newer build and installs it.
 * GitHub Actions publishes thai-vocab-recorder.apk and version.json there for every
 * build of the default branch. The app's versionCode is the GitHub build number.
 */
class Updater(private val context: Context) {

    data class Available(val versionCode: Long, val versionName: String)

    val installedVersion: Long
        get() = PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))

    /** Returns the newer build, or null when the installed build is current (or on any error). */
    suspend fun check(): Available? = withContext(Dispatchers.IO) {
        runCatching {
            val json = JSONObject(String(get("$RELEASE_URL/${AppProfile.releaseVersionFile}")))
            val code = json.getLong("versionCode")
            if (code > installedVersion) Available(code, json.optString("versionName", code.toString())) else null
        }.getOrNull()
    }

    /** Downloads the APK. [onProgress] receives 0..1. Returns the file. */
    suspend fun download(onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // old downloads
        val target = File(dir, AppProfile.releaseApk)
        val conn = open("$RELEASE_URL/${AppProfile.releaseApk}")
        try {
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: -1
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        target
    }

    /**
     * Opens Android's installer for the downloaded APK. The first time, Android asks to
     * allow installs from this app ("Install unknown apps"); after that it is one tap.
     * The app keeps all its data, because every build has the same signature.
     */
    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun get(url: String): ByteArray {
        val conn = open(url)
        try {
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    /** Opens a connection and follows GitHub's redirects to its download server. */
    private fun open(url: String): HttpURLConnection {
        var current = url
        repeat(5) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("Cache-Control", "no-cache")
            val code = conn.responseCode
            if (code in 300..399) {
                current = conn.getHeaderField("Location") ?: error("Redirect without location")
                conn.disconnect()
            } else if (code in 200..299) {
                return conn
            } else {
                conn.disconnect()
                error("HTTP $code for $current")
            }
        }
        error("Too many redirects")
    }

    private companion object {
        const val RELEASE_URL =
            "https://github.com/merlin-lacuna/thai-vocab-recorder/releases/download/latest"
    }
}
