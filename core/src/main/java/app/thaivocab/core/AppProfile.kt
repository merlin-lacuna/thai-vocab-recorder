package app.thaivocab.core

/**
 * Values that differ between the two apps. Each app sets them in Application.onCreate,
 * before any other code of this module runs.
 */
object AppProfile {
    /** Written to vocab_items.source for rows that this app creates. */
    var source: String = "field_recorder"

    /** File names in the "latest" GitHub release. */
    var releaseApk: String = "thai-vocab-recorder.apk"
    var releaseVersionFile: String = "version.json"
}
