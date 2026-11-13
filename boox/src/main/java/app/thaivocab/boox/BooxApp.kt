package app.thaivocab.boox

import android.app.Application
import app.thaivocab.core.AppProfile
import org.lsposed.hiddenapibypass.HiddenApiBypass

class BooxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppProfile.source = "boox_handwriting"
        AppProfile.releaseApk = "thai-vocab-boox.apk"
        AppProfile.releaseVersionFile = "version-boox.json"
        // The Onyx pen library calls hidden Android APIs.
        runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
    }
}
