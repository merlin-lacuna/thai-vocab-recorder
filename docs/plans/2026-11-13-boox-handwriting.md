# Boox Handwriting Capture Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a second Android app for the Boox Note Air2 Plus that saves handwritten Thai words into the existing `vocab_items` table, with handwriting read by Claude on the server.

**Architecture:** The repository becomes three Gradle modules: `:core` (store, Supabase client, sync job, updater, shared dialogs), `:app` (phone recorder, unchanged behaviour) and `:boox` (tablet app). The tablet app renders pen strokes to PNG files, the shared sync job uploads them, and a new `read` step in the existing Edge Function `process` sends the images to Claude. The existing `enrich` step then adds romanization and meaning.

**Tech Stack:** Kotlin 2.1, Jetpack Compose (BOM 2024.12.01), WorkManager, SQLite, Onyx pen library (`onyxsdk-pen` 1.5.9), Supabase (PostgreSQL, Storage, Edge Functions on Deno), Anthropic TypeScript SDK, GitHub Actions.

**Design document:** `docs/plans/2026-11-13-boox-handwriting-design.md`. Read it first.

---

## Facts that the executor needs

- **Branch:** work on `boox-handwriting` (already created from `claude/elegant-gates-odn9kz`, which is the default branch of the repository).
- **JDK:** the `java` on the PATH is version 8 and cannot run Gradle. Use JDK 21:
  `export JAVA_HOME="$HOME/.jdks/jbr-21.0.11"` in every shell before `./gradlew`.
- **adb:** `"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"`. The tablet has the serial `AAFF665D`.
- **Tablet:** Android 11 (API 30), 1404 × 1872 pixels at 240 dpi, which is 936 × 1248 dp.
- **Onyx library versions** (read from `https://repo.boox.com/repository/maven-public/` on 2026-11-13): `onyxsdk-pen` 1.5.9, `onyxsdk-base` 1.8.10, `onyxsdk-device` 1.3.7.
- **Rules in `CLAUDE.md`:** database changes go in a new migration file. Every thing that the user creates must be editable and deletable by a tap on the thing itself. Describe software in mechanical terms.
- **Commits:** end every commit message with `Co-Authored-By: Claude Fable 5.2 <noreply@anthropic.com>`. Do not push unless Merlin asks.
- **Before editing the Edge Function:** load the `claude-api` skill, because the function calls the Anthropic SDK.

## Two points that need Merlin

1. **Deploying the Edge Function and the migration.** The repository does not document how `process` was deployed. Ask Merlin before Task 6, step 6.
2. **Handwriting samples.** Task 6 needs four photographs or PNG files of Merlin's real handwriting. Ask for them when Task 5 starts.

---

### Task 1: Baseline build

**Files:** none.

**Step 1: Build the recorder as it is**

Run:
```bash
export JAVA_HOME="$HOME/.jdks/jbr-21.0.11" && ./gradlew --no-daemon :app:assembleDebug :app:recordPaparazziDebug
```
Expected: `BUILD SUCCESSFUL`. If this fails, stop and fix the environment before any code change.

---

### Task 2: Create the `:core` module and move the shared code

**Files:**
- Create: `core/build.gradle.kts`, `core/consumer-rules.pro`, `core/src/main/AndroidManifest.xml`
- Create: `core/src/main/java/app/thaivocab/core/AppProfile.kt`
- Move: `app/src/main/java/app/thaivocab/recorder/{data,sync,update}/*.kt` → `core/src/main/java/app/thaivocab/core/{data,sync,update}/`
- Modify: `settings.gradle.kts`, `build.gradle.kts`, `app/build.gradle.kts`, `app/proguard-rules.pro`
- Modify: every file in `app/src/main/java/app/thaivocab/recorder/ui/` and `app/src/test/` (imports)

**Step 1: Register the module**

In `settings.gradle.kts`, replace `include(":app")` with:
```kotlin
include(":app", ":core")
```
In the root `build.gradle.kts`, add inside `plugins { }`:
```kotlin
    id("com.android.library") version "8.7.3" apply false
```

**Step 2: Write `core/build.gradle.kts`**

```kotlin
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.thaivocab.core"
    compileSdk = 35

    defaultConfig {
        minSdk = 30 // the Boox tablet runs Android 11
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    api(composeBom)
    api("androidx.compose.material3:material3")
    api("androidx.compose.material:material-icons-extended")
    api("androidx.compose.ui:ui")
    api("androidx.compose.ui:ui-tooling-preview")
    api("androidx.activity:activity-compose:1.9.3")
    api("androidx.core:core-ktx:1.15.0")
    api("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    api("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    api("androidx.work:work-runtime-ktx:2.10.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    // The Android unit-test jar contains org.json as empty stubs. This is the real library.
    testImplementation("org.json:json:20240303")
}
```

`core/consumer-rules.pro`:
```
# WorkManager creates the worker by reflection.
-keep class app.thaivocab.core.sync.SyncWorker { <init>(...); }
```

`core/src/main/AndroidManifest.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest />
```

**Step 3: Move the files and rename the packages**

```bash
mkdir -p core/src/main/java/app/thaivocab/core
git mv app/src/main/java/app/thaivocab/recorder/data   core/src/main/java/app/thaivocab/core/data
git mv app/src/main/java/app/thaivocab/recorder/sync   core/src/main/java/app/thaivocab/core/sync
git mv app/src/main/java/app/thaivocab/recorder/update core/src/main/java/app/thaivocab/core/update
grep -rl "app\.thaivocab\.recorder\.\(data\|sync\|update\)" app core \
  | xargs sed -i -E 's/app\.thaivocab\.recorder\.(data|sync|update)/app.thaivocab.core.\1/g'
```

**Step 4: Write `AppProfile.kt`**

```kotlin
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
```

**Step 5: Use `AppProfile` in the moved code**

- `core/.../sync/SyncWorker.kt`: delete `const val SOURCE = "field_recorder"`. Replace the one use `str("source") ?: SOURCE` with `str("source") ?: AppProfile.source`. Add `import app.thaivocab.core.AppProfile`.
- `core/.../data/VocabStore.kt`: in `data class VocabEntry`, change `val source: String = "field_recorder"` to `val source: String = AppProfile.source`. In `toEntry`, change `str("source") ?: "field_recorder"` to `str("source") ?: AppProfile.source`. Add the import.
- `core/.../update/Updater.kt`:
  - Replace the two literal file names: `"$RELEASE_URL/version.json"` → `"$RELEASE_URL/${AppProfile.releaseVersionFile}"`, and both `thai-vocab-recorder.apk` → `AppProfile.releaseApk`.
  - Replace `installedVersion`, because `PackageInfoFlags` exists only from Android 13:
    ```kotlin
    val installedVersion: Long
        get() = PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))
    ```
    Add `import androidx.core.content.pm.PackageInfoCompat` and remove `import android.content.pm.PackageManager`.

**Step 6: Point the recorder at `:core`**

In `app/build.gradle.kts`, replace the whole `dependencies { }` block with:
```kotlin
dependencies {
    implementation(project(":core"))
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
```
In `app/proguard-rules.pro`, delete the two lines about `SyncWorker` (the rule now comes from `core/consumer-rules.pro`).

**Step 7: Build**

Run: `./gradlew --no-daemon :app:assembleDebug :app:assembleRelease :app:recordPaparazziDebug`
Expected: `BUILD SUCCESSFUL`. Fix missing imports until it passes.

**Step 8: Commit**

```bash
git add -A && git commit -m "Move store, sync and updater into a shared core module"
```

---

### Task 3: Move the connection logic and the shared dialogs into `:core`

The tablet app needs the connection window and the labels windows. `SetupDialog` takes the recorder's `MainViewModel` today, so the functions that the dialog calls move into a class in `:core`.

**Files:**
- Create: `core/src/main/java/app/thaivocab/core/sync/Connection.kt`
- Create: `core/src/main/java/app/thaivocab/core/ui/SharedDialogs.kt`
- Modify: `app/src/main/java/app/thaivocab/recorder/ui/Dialogs.kt`, `MainViewModel.kt`, `MainScreen.kt`

**Step 1: Write `Connection.kt`**

Move the bodies of `connect`, `saveSetupDraft`, `disconnect`, `draftPassword` and `syncNow` out of `MainViewModel`:
```kotlin
package app.thaivocab.core.sync

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
            } catch (e: Exception) {
                e.message ?: e.toString()
            }
        }

    fun saveSetupDraft(url: String, apiKey: String, email: String) = config.saveDraft(url, apiKey, email)

    fun disconnect() = config.save { remove("refresh_token"); remove("user_id") }

    fun syncNow() = SyncWorker.schedule(app)
}
```

**Step 2: Use `Connection` in the recorder**

In `MainViewModel.kt`: add `val connection = Connection(app)`, delete `connect`, `draftPassword`, `saveSetupDraft` and `disconnect`, and change `fun syncNow()` to `fun syncNow() = connection.syncNow()`.

**Step 3: Move three dialogs**

Cut `SetupDialog`, `LabelsDialog` and `LabelActionDialog` from `app/.../ui/Dialogs.kt` and paste them into `core/.../ui/SharedDialogs.kt` with `package app.thaivocab.core.ui`. Copy the imports that they use. Change the signature of `SetupDialog`:
```kotlin
fun SetupDialog(connection: Connection, state: SupabaseConfig.State, onDismiss: () -> Unit) {
```
Inside the function, replace every `vm.` with `connection.`.

In `MainScreen.kt`, change the call to `SetupDialog(connection = vm.connection, state = supabase, onDismiss = { showSetup = false })` and import the three dialogs from `app.thaivocab.core.ui`. Do the same in `Previews.kt` and `ScreenshotTest.kt` if they reference the dialogs.

**Step 4: Build**

Run: `./gradlew --no-daemon :app:assembleDebug :app:recordPaparazziDebug`
Expected: `BUILD SUCCESSFUL`.

**Step 5: Commit**

```bash
git add -A && git commit -m "Move connection logic and shared dialogs into core"
```

---

### Task 4: Handwriting fields in the local store and the row conversion (TDD)

**Files:**
- Create: `core/src/main/java/app/thaivocab/core/sync/RowMapping.kt`
- Create: `core/src/test/java/app/thaivocab/core/sync/RowMappingTest.kt`
- Modify: `core/src/main/java/app/thaivocab/core/data/VocabStore.kt`
- Modify: `core/src/main/java/app/thaivocab/core/sync/SyncWorker.kt`

**Step 1: Add the fields to `VocabEntry` and the status `GUESSED`**

In `VocabStore.kt`:
```kotlin
/** How far the text recognition step (speech or handwriting) has come for one entry. */
enum class Transcription { PENDING, DONE, GUESSED, FAILED, MANUAL }
```
Add to `data class VocabEntry`, after `nativeCleared`:
```kotlin
    /** Handwriting from the tablet: local PNG of the upper zone (Thai or phonetic). */
    val imageFile: String? = null,
    /** Local PNG of the lower zone (the user's English translation), if the user wrote one. */
    val englishImageFile: String? = null,
    val imagesUploaded: Boolean = true,
    /** The user removed the handwriting image: the next upload empties the paths on the server. */
    val imageCleared: Boolean = false,
    /** "thai" or "phonetic": which Next button the user pressed. */
    val writtenScript: String? = null,
    /** The two zones as Claude read them. Read-only on the device. */
    val writtenText: String? = null,
    val writtenEnglish: String? = null,
```

**Step 2: Write the failing test**

`core/src/test/java/app/thaivocab/core/sync/RowMappingTest.kt`:
```kotlin
package app.thaivocab.core.sync

import app.thaivocab.core.data.CaptureContext
import app.thaivocab.core.data.Transcription
import app.thaivocab.core.data.VocabEntry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RowMappingTest {
    private fun entry(
        imageFile: String? = null, englishImageFile: String? = null, imageCleared: Boolean = false,
        script: String? = null, transcription: Transcription = Transcription.PENDING,
    ) = VocabEntry(
        id = "id-1", thai = null, alternatives = emptyList(), notes = null, audioFile = null,
        capturedAt = 0, updatedAt = 0, context = CaptureContext.KRU_BOOM, source = "boox_handwriting",
        imageFile = imageFile, englishImageFile = englishImageFile, imageCleared = imageCleared,
        writtenScript = script, transcription = transcription,
        dirty = true, audioUploaded = false, deleted = false,
    )

    @Test fun `status text from the server becomes the enum`() {
        assertEquals(Transcription.GUESSED, transcriptionOf("guessed", hasThai = true))
        assertEquals(Transcription.DONE, transcriptionOf("done", hasThai = true))
        assertEquals(Transcription.PENDING, transcriptionOf("pending", hasThai = false))
        assertEquals(Transcription.MANUAL, transcriptionOf(null, hasThai = true))
        assertEquals(Transcription.FAILED, transcriptionOf(null, hasThai = false))
    }

    @Test fun `row of a handwriting entry carries the image paths and the script`() {
        val row = entry(imageFile = "/x/id-1.png", englishImageFile = "/x/id-1-en.png", script = "phonetic")
            .toRow(userId = "u1")
        assertEquals("u1/id-1.png", row.getString("image_path"))
        assertEquals("u1/id-1-en.png", row.getString("english_image_path"))
        assertEquals("phonetic", row.getString("written_script"))
        assertEquals("boox_handwriting", row.getString("source"))
        assertEquals("pending", row.getString("transcription_status"))
        assertEquals("Boom", row.getString("teacher"))
    }

    @Test fun `row of an entry without handwriting leaves the image columns out`() {
        val row = entry().toRow(userId = "u1")
        assertFalse(row.has("image_path"))
        assertFalse(row.has("english_image_path"))
        assertFalse(row.has("written_script"))
    }

    @Test fun `removed handwriting image sends null paths`() {
        val row = entry(imageCleared = true).toRow(userId = "u1")
        assertTrue(row.isNull("image_path") && row.has("image_path"))
        assertTrue(row.isNull("english_image_path") && row.has("english_image_path"))
    }

    @Test fun `guessed status is written in lower case`() {
        val row = entry(transcription = Transcription.GUESSED).toRow(userId = "u1")
        assertEquals("guessed", row.getString("transcription_status"))
    }

    @Test fun `audio path is derived from the user id`() {
        val row = entry().copy(audioFile = "/x/id-1.wav").toRow(userId = "u1")
        assertEquals("u1/id-1.wav", row.getString("audio_path"))
        assertTrue(JSONObject(entry().toRow("u1").toString()).isNull("audio_path"))
    }
}
```

**Step 3: Run the test and see it fail**

Run: `./gradlew --no-daemon :core:testDebugUnitTest`
Expected: compilation fails with "Unresolved reference: transcriptionOf" and "toRow".

**Step 4: Write `RowMapping.kt`**

Move `VocabEntry.toRow` out of `SyncWorker` and add the new columns:
```kotlin
package app.thaivocab.core.sync

import app.thaivocab.core.data.Transcription
import app.thaivocab.core.data.VocabEntry
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

const val AUDIO_BUCKET = "vocab-audio"
const val HANDWRITING_BUCKET = "vocab-handwriting"

fun audioPath(userId: String, id: String) = "$userId/$id.wav"
fun imagePath(userId: String, id: String) = "$userId/$id.png"
fun englishImagePath(userId: String, id: String) = "$userId/$id-en.png"

/** Maps vocab_items.transcription_status to the local enum. */
fun transcriptionOf(status: String?, hasThai: Boolean): Transcription = when (status) {
    "done" -> Transcription.DONE
    "guessed" -> Transcription.GUESSED
    "failed" -> Transcription.FAILED
    "pending" -> Transcription.PENDING
    else -> if (hasThai) Transcription.MANUAL else Transcription.FAILED
}

/** The JSON row that the sync job upserts into vocab_items. */
fun VocabEntry.toRow(userId: String) = JSONObject().apply {
    put("id", id)
    put("thai", thai ?: JSONObject.NULL)
    put("thai_alternatives", JSONArray(alternatives))
    put("notes", notes ?: JSONObject.NULL)
    put("audio_path", audioFile?.let { audioPath(userId, id) } ?: JSONObject.NULL)
    put("source", source)
    // Native clip fields only when known, so a stale device never erases a clip that a
    // batch job added in the meantime.
    if (nativeCleared) {
        put("native_audio_path", JSONObject.NULL)
        put("native_audio_source", JSONObject.NULL)
    } else {
        nativeAudioPath?.let { put("native_audio_path", it) }
        nativeAudioSource?.let { put("native_audio_source", it) }
    }
    // Handwriting columns, by the same rule.
    if (imageCleared) {
        put("image_path", JSONObject.NULL)
        put("english_image_path", JSONObject.NULL)
    } else {
        imageFile?.let { put("image_path", imagePath(userId, id)) }
        englishImageFile?.let { put("english_image_path", englishImagePath(userId, id)) }
    }
    writtenScript?.let { put("written_script", it) }
    put("context", context.dbValue)
    put("teacher", context.teacher ?: JSONObject.NULL)
    put("transcription_error", transcriptionError ?: JSONObject.NULL)
    put("transcription_status", transcription.name.lowercase())
    put("captured_at", Instant.ofEpochMilli(capturedAt).toString())
    put("updated_at", Instant.ofEpochMilli(updatedAt).toString())
    put("deleted_at", if (deleted) Instant.ofEpochMilli(updatedAt).toString() else JSONObject.NULL)
}
```
In `SyncWorker.kt`: delete the private `toRow`, delete `const val AUDIO_BUCKET`, change the call to `toSend.toRow(session.userId)`, replace `"${session.userId}/${entry.id}.wav"` with `audioPath(session.userId, entry.id)`, and replace the `when` in `JSONObject.toEntry` with `transcriptionOf(str("transcription_status"), thai != null)`.

**Step 5: Run the test and see it pass**

Run: `./gradlew --no-daemon :core:testDebugUnitTest`
Expected: 6 tests pass.

**Step 6: Add the columns to the local SQLite table**

In `VocabStore.kt`:
- Change the database version from `6` to `7`.
- Add to the `CREATE TABLE` statement, after `native_cleared`:
  ```sql
                  image_file TEXT,
                  english_image_file TEXT,
                  images_uploaded INTEGER NOT NULL DEFAULT 1,
                  image_cleared INTEGER NOT NULL DEFAULT 0,
                  written_script TEXT,
                  written_text TEXT,
                  written_english TEXT
  ```
  (add a comma after the `native_cleared` line).
- Add to `onUpgrade`:
  ```kotlin
        if (oldVersion < 7) {
            db.execSQL("ALTER TABLE entries ADD COLUMN image_file TEXT")
            db.execSQL("ALTER TABLE entries ADD COLUMN english_image_file TEXT")
            db.execSQL("ALTER TABLE entries ADD COLUMN images_uploaded INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE entries ADD COLUMN image_cleared INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE entries ADD COLUMN written_script TEXT")
            db.execSQL("ALTER TABLE entries ADD COLUMN written_text TEXT")
            db.execSQL("ALTER TABLE entries ADD COLUMN written_english TEXT")
        }
  ```
- Add to `toValues`:
  ```kotlin
        put("image_file", imageFile)
        put("english_image_file", englishImageFile)
        put("images_uploaded", if (imagesUploaded) 1 else 0)
        put("image_cleared", if (imageCleared) 1 else 0)
        put("written_script", writtenScript)
        put("written_text", writtenText)
        put("written_english", writtenEnglish)
  ```
- Add to `toEntry`:
  ```kotlin
            imageFile = str("image_file"),
            englishImageFile = str("english_image_file"),
            imagesUploaded = long("images_uploaded") == 1L,
            imageCleared = long("image_cleared") == 1L,
            writtenScript = str("written_script"),
            writtenText = str("written_text"),
            writtenEnglish = str("written_english"),
  ```
- Add a function next to `markAudioUploaded`:
  ```kotlin
    @Synchronized
    fun markImagesUploaded(id: String) {
        val values = ContentValues().apply { put("images_uploaded", 1) }
        writableDatabase.update("entries", values, "id = ?", arrayOf(id))
    }
  ```
- In `markSynced`, inside `if (unchanged && e.deleted)`, add:
  ```kotlin
            e.imageFile?.let { File(it).delete() }
            e.englishImageFile?.let { File(it).delete() }
  ```

**Step 7: Build and commit**

Run: `./gradlew --no-daemon :core:testDebugUnitTest :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.
```bash
git add -A && git commit -m "Add handwriting fields to the local store and the row conversion"
```

---

### Task 5: Database migration

**Files:**
- Create: `supabase/migrations/20261113120000_handwriting.sql`

**Step 1: Write the migration**

```sql
-- Handwriting from the Boox tablet app (source = 'boox_handwriting').
-- The writing box has two zones. Each zone is stored as one PNG file in the private
-- Storage bucket "vocab-handwriting":
--   upper zone (Thai script or phonetic Latin letters): "<user id>/<item id>.png"
--   lower zone (the learner's English translation):     "<user id>/<item id>-en.png"
alter table public.vocab_items add column if not exists image_path text;
alter table public.vocab_items add column if not exists english_image_path text;

-- Which "Next" button the user pressed. The "read" step of the Edge Function uses it.
alter table public.vocab_items add column if not exists written_script text
    check (written_script in ('thai', 'phonetic'));

-- The two zones as Claude read them. `thai` holds the Thai word; for phonetic writing,
-- `thai` is a guess and transcription_status is 'guessed'.
alter table public.vocab_items add column if not exists written_text text;
alter table public.vocab_items add column if not exists written_english text;

insert into storage.buckets (id, name, public)
values ('vocab-handwriting', 'vocab-handwriting', false)
on conflict (id) do nothing;

drop policy if exists "own handwriting read" on storage.objects;
create policy "own handwriting read" on storage.objects
    for select to authenticated
    using (bucket_id = 'vocab-handwriting' and (storage.foldername(name))[1] = auth.uid()::text);

drop policy if exists "own handwriting insert" on storage.objects;
create policy "own handwriting insert" on storage.objects
    for insert to authenticated
    with check (bucket_id = 'vocab-handwriting' and (storage.foldername(name))[1] = auth.uid()::text);

drop policy if exists "own handwriting update" on storage.objects;
create policy "own handwriting update" on storage.objects
    for update to authenticated
    using (bucket_id = 'vocab-handwriting' and (storage.foldername(name))[1] = auth.uid()::text);

drop policy if exists "own handwriting delete" on storage.objects;
create policy "own handwriting delete" on storage.objects
    for delete to authenticated
    using (bucket_id = 'vocab-handwriting' and (storage.foldername(name))[1] = auth.uid()::text);

-- Views store their own column names: recreate the view that lists all columns.
drop view if exists public.vocab_active;
create view public.vocab_active with (security_invoker = true) as
    select * from public.vocab_items
    where deleted_at is null and quality = 'ok';
```

**Step 2: Commit**

```bash
git add supabase/migrations/20261113120000_handwriting.sql && git commit -m "Add handwriting columns and storage bucket"
```
The migration is applied in Task 6, step 6, together with the function.

---

### Task 6: New step `read` in the Edge Function `process`

**Files:**
- Modify: `supabase/functions/process/index.ts`

Load the `claude-api` skill before this task.

**Step 1: Share the Claude call between `enrich` and `read`**

Add the import at the top:
```ts
import { encodeBase64 } from "jsr:@std/encoding@1/base64";
```
Extract the request and the error handling from `enrich` into one helper, placed above `enrich`:
```ts
// deno-lint-ignore no-explicit-any
async function askClaude(content: any, schema: unknown): Promise<any> {
  // Reads ANTHROPIC_API_KEY from the environment. One retry inside the SDK, then the
  // next sync tries again; 30 s limit so the device (60 s limit) always gets an answer.
  anthropic ??= new Anthropic({ timeout: 30_000, maxRetries: 1 });
  let response;
  try {
    response = await anthropic.beta.messages.create({
      model: Deno.env.get("CLAUDE_MODEL") ?? "claude-sonnet-5-5",
      max_tokens: 4000,
      // If a safety classifier declines, the API re-runs the request on a fallback model.
      betas: ["server-side-fallback-2026-07-01"],
      fallbacks: "default",
      output_config: {
        effort: (Deno.env.get("CLAUDE_EFFORT") ?? "low") as "low" | "medium" | "high" | "xhigh" | "max",
        format: { type: "json_schema", schema },
      },
      messages: [{ role: "user", content }],
    });
  } catch (e) {
    if (
      e instanceof Anthropic.RateLimitError ||
      e instanceof Anthropic.InternalServerError ||
      e instanceof Anthropic.APIConnectionError // includes timeouts
    ) {
      throw new TemporaryError(`Claude: ${e.message}`);
    }
    throw e; // 400/401/403/404: permanent, saved as an error in the row
  }
  if (response.stop_reason === "refusal") throw new Error("Claude declined the request");
  if (response.stop_reason === "max_tokens") throw new Error("Claude answer was cut off");
  let text = "";
  for (const block of response.content) {
    if (block.type === "text") text += block.text;
  }
  if (!text) throw new Error("Claude returned no text");
  return JSON.parse(text);
}
```
In `enrich`, replace everything from `let response;` to `const result = JSON.parse(text) as Enrichment;` with:
```ts
  const result = await askClaude(prompt, ENRICHMENT_SCHEMA) as Enrichment;
```

**Step 2: Tell `enrich` about handwriting**

Change the signature to `enrich(thai: string, notes: string | null, handwriting: boolean, writtenEnglish: string | null)`. Replace the first paragraph of the prompt (up to "pronounced wrongly.") with:
```ts
  const origin = handwriting
    ? `You help an English speaker who learns Thai. They wrote a Thai word or phrase by hand
during a lesson, and a model read the handwriting. The text can contain a wrong letter where
two Thai letters look alike in handwriting (for example ด and ค, ถ and ภ, บ and ป).`
    : `You help an English speaker who learns Thai. They heard a Thai word or phrase
and recorded it, often in their own (learner) pronunciation. Speech recognition produced this
text, which can be misspelled: it often writes the sound instead of the dictionary spelling,
for example it drops silent letters (ุ in เหตุ, ์ karan) or a quiet final consonant, or it
writes a different word when a tone or vowel length was pronounced wrongly.`;
```
Start the prompt with `${origin}`, and add after the notes line:
```ts
${writtenEnglish ? `The learner's own translation: ${writtenEnglish}\n` : ""}
```
Change the call in the request handler to:
```ts
      const r = await enrich(thai, row.notes, row.source === "boox_handwriting", row.written_english ?? null);
```

**Step 3: Add the `read` function**

Place above `Deno.serve`:
```ts
interface Reading {
  written_text: string;
  thai: string;
  thai_alternatives: string[];
  written_english: string | null;
}

const READING_SCHEMA = {
  type: "object",
  properties: {
    written_text: { type: "string" },
    thai: { type: "string" },
    thai_alternatives: { type: "array", items: { type: "string" } },
    written_english: { anyOf: [{ type: "string" }, { type: "null" }] },
  },
  required: ["written_text", "thai", "thai_alternatives", "written_english"],
  additionalProperties: false,
};

async function imageBlock(supabase: SupabaseClient, path: string) {
  const { data, error } = await supabase.storage.from("vocab-handwriting").download(path);
  if (error || !data) throw new Error(`Cannot read image: ${error?.message ?? "not found"}`);
  return {
    type: "image",
    source: { type: "base64", media_type: "image/png", data: encodeBase64(await data.arrayBuffer()) },
  };
}

/** Reads the handwriting images of one row. `script` is the Next button the user pressed. */
async function read(supabase: SupabaseClient, row: Row): Promise<Reading> {
  const phonetic = row.written_script === "phonetic";
  // deno-lint-ignore no-explicit-any
  const content: any[] = [
    { type: "text", text: "Image 1: the word or phrase that the learner wrote." },
    await imageBlock(supabase, row.image_path),
  ];
  if (row.english_image_path) {
    content.push({ type: "text", text: "Image 2: the learner's own English translation of it." });
    content.push(await imageBlock(supabase, row.english_image_path));
  }
  const task = phonetic
    ? `Image 1 contains Latin letters: the learner wrote a Thai word or phrase by its sound, in
their own informal spelling, because they did not see the Thai script. Tones and vowel length
are usually not marked.
Return:
- written_text: the Latin letters exactly as written.
- thai: the Thai script of the most likely intended word or phrase.
- thai_alternatives: up to four other Thai words or phrases that this spelling can mean,
  most likely first. Use an empty list when there is only one plausible answer.`
    : `Image 1 contains handwritten Thai script by a learner. Letter shapes can be imperfect.
Return:
- written_text: the Thai text exactly as you read it.
- thai: the same text as written_text.
- thai_alternatives: up to four other readings where a letter or a tone mark is unclear,
  most likely first. Use an empty list when the handwriting is clear.`;
  content.push({
    type: "text",
    text: `You help an English speaker who learns Thai. They wrote the text in the image by hand
with a pen on a tablet during a Thai lesson.

${task}
- written_english: ${row.english_image_path
      ? "the English text in image 2, exactly as written. Use it as a hint for image 1."
      : "null."}
${row.notes ? `\nThe learner's note: ${row.notes}` : ""}`,
  });
  const result = await askClaude(content, READING_SCHEMA) as Reading;
  if (!row.english_image_path) result.written_english = null;
  return result;
}
```

**Step 4: Call `read` from the request handler**

- Change the step check to `["transcribe", "read", "enrich", "all"]`.
- Change the header comment of the file: add `"read"` to the input line and describe the step in two lines.
- Change the condition of step 1 (transcription) from `step !== "enrich"` to `(step === "transcribe" || step === "all")`.
- Change the condition of step 2 (enrichment) from `step !== "transcribe"` to `(step === "enrich" || step === "all")`.
- Insert between step 1 and step 2:
```ts
  // Step 1b: handwriting from the tablet. Runs for a new row, and again when the app sets
  // the status back to "pending" ("Read again as the other type").
  if (
    (step === "read" || step === "all") && row.image_path &&
    row.enrichment_status !== "reviewed" && row.transcription_status !== "manual" &&
    (!row.thai || row.transcription_status === "pending")
  ) {
    try {
      const r = await read(supabase, row);
      const thai = r.thai.trim();
      if (thai) {
        Object.assign(update, {
          thai,
          thai_alternatives: r.thai_alternatives.filter((a) => a && a !== thai).slice(0, 4),
          written_text: r.written_text,
          written_english: r.written_english,
          transcription_status: row.written_script === "phonetic" ? "guessed" : "done",
          transcription_error: null,
          // The meaning of an earlier reading no longer fits: let "enrich" run again.
          romanization: null,
          english: null,
          enrichment_status: "new",
          enrichment_error: null,
        });
      } else {
        Object.assign(update, { transcription_status: "failed", transcription_error: "no text found" });
      }
    } catch (e) {
      if (e instanceof TemporaryError) return json({ error: String(e) }, 503);
      Object.assign(update, { transcription_status: "failed", transcription_error: String(e).slice(0, 300) });
    }
  }
```
The 503 answer makes the sync job on the device keep the entry as "not uploaded" and retry later.

- In step 2, the line `const alternatives: string[] = [...(row.thai_alternatives ?? [])];` must use the new alternatives when step 1b ran in the same call. Change it to:
```ts
      const alternatives: string[] = [...(update.thai_alternatives ?? row.thai_alternatives ?? [])];
```

**Step 5: Check the syntax**

Run: `npx --yes deno check supabase/functions/process/index.ts`
Expected: no errors. (If `npx deno` is unavailable, run `npx --yes supabase functions serve` is not required; a type check in the Supabase dashboard editor is also acceptable.)

**Step 6: Deploy and test with real handwriting (needs Merlin)**

1. Ask Merlin how the function and the migrations were deployed before, and deploy both the same way. If Merlin has the Supabase CLI login, the commands are:
   ```bash
   npx --yes supabase db push --project-ref vazojhdllcilzbdtavww
   npx --yes supabase functions deploy process --project-ref vazojhdllcilzbdtavww
   ```
2. Ask Merlin for four PNG files of real handwriting: one Thai word, one Thai phrase, one phonetic word, and one phonetic word with an English translation as a second file.
3. For each sample: upload the file to `vocab-handwriting/<user id>/<new uuid>.png` in the dashboard, insert a row in `vocab_items` with that `id`, `image_path`, `written_script`, `source = 'boox_handwriting'` and `transcription_status = 'pending'`, then call the function with Merlin's login token:
   ```bash
   curl -s -X POST "https://vazojhdllcilzbdtavww.supabase.co/functions/v1/process" \
     -H "Authorization: Bearer $TOKEN" -H "apikey: $PUBLISHABLE_KEY" -H "Content-Type: application/json" \
     -d '{"item_id":"<uuid>","step":"read"}'
   ```
   Expected: the returned row has the correct `thai`, and `transcription_status` is `done` for Thai script and `guessed` for phonetic writing.
4. Call the function again with `"step":"enrich"` for one row. Expected: `romanization` and `english` are filled.
5. Record the four results in the commit message. If a reading is wrong, adjust the prompt in `read` and repeat.

**Step 7: Commit**

```bash
git add supabase/functions/process/index.ts && git commit -m "Add handwriting step 'read' to the process function"
```

---

### Task 7: Sync job uploads images and calls `read`

**Files:**
- Modify: `core/src/main/java/app/thaivocab/core/sync/SyncWorker.kt`
- Modify: `core/src/main/java/app/thaivocab/core/sync/Supabase.kt` (comment of `process` only)

**Step 1: Upload the images**

In `doWork`, after the block that uploads the native clip and before `api.upsert(...)`, add:
```kotlin
                // Handwriting images from the tablet.
                if (!entry.deleted && !entry.imagesUploaded) {
                    entry.imageFile?.let { File(it) }?.takeIf { it.exists() }?.let {
                        api.uploadFile(session.accessToken, HANDWRITING_BUCKET, imagePath(session.userId, entry.id), it, "image/png")
                    }
                    entry.englishImageFile?.let { File(it) }?.takeIf { it.exists() }?.let {
                        api.uploadFile(session.accessToken, HANDWRITING_BUCKET, englishImagePath(session.userId, entry.id), it, "image/png")
                    }
                    store.markImagesUploaded(entry.id)
                }
```

**Step 2: Choose the server step**

Replace `api.process(session.accessToken, entry.id, "transcribe")` with:
```kotlin
                        // Handwriting is read by Claude; a recording is transcribed by ElevenLabs.
                        val step = if (entry.imageFile != null) "read" else "transcribe"
                        transcribed = api.process(session.accessToken, entry.id, step).toEntry(audioDir)
```
Update the comment above the block to name both cases.

**Step 3: Download the new columns**

- In `pull`, add to the `select=` list: `image_path,english_image_path,written_script,written_text,written_english`.
- In `JSONObject.toEntry`, add (the directory `handwriting` is a sibling of `audio`):
```kotlin
            imageFile = str("image_path")?.let { File(audioDir.parentFile, "handwriting/$id.png").absolutePath },
            englishImageFile = str("english_image_path")?.let { File(audioDir.parentFile, "handwriting/$id-en.png").absolutePath },
            imagesUploaded = true,
            writtenScript = str("written_script"),
            writtenText = str("written_text"),
            writtenEnglish = str("written_english"),
```
- At the end of `pull`, add:
```kotlin
        // Fetch handwriting images that are missing on the device, newest first.
        remote.asSequence().filter { !it.deleted }.take(AUDIO_DOWNLOAD_LIMIT).forEach { e ->
            listOf(e.imageFile to imagePath(userId, e.id), e.englishImageFile to englishImagePath(userId, e.id))
                .forEach { (local, path) ->
                    val target = local?.let { File(it) } ?: return@forEach
                    if (target.exists()) return@forEach
                    target.parentFile?.mkdirs()
                    val tmp = File(target.path + ".part")
                    tmp.writeBytes(api.downloadFile(token, HANDWRITING_BUCKET, path))
                    tmp.renameTo(target)
                }
        }
```
`take(AUDIO_DOWNLOAD_LIMIT)` here limits the rows that the loop checks, which is acceptable because the files are small.

**Step 4: Build and commit**

Run: `./gradlew --no-daemon :core:testDebugUnitTest :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.
```bash
git add -A && git commit -m "Sync job: upload handwriting images and call the read step"
```

---

### Task 8: Ink geometry (TDD)

Pure Kotlin, so the tests run on the computer.

**Files:**
- Create: `settings.gradle.kts` entry and `boox/build.gradle.kts` (see step 1)
- Create: `boox/src/main/java/app/thaivocab/boox/ink/Ink.kt`
- Create: `boox/src/test/java/app/thaivocab/boox/ink/InkTest.kt`

**Step 1: Create the `:boox` module**

`settings.gradle.kts`: change to `include(":app", ":core", ":boox")`, and add the Onyx repository inside `dependencyResolutionManagement { repositories { } }`:
```kotlin
        maven { url = uri("https://repo.boox.com/repository/maven-public/") }
```

`boox/build.gradle.kts`:
```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("app.cash.paparazzi")
}

android {
    namespace = "app.thaivocab.boox"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.thaivocab.boox"
        minSdk = 30 // Boox Note Air2 Plus: Android 11
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
    }

    // Same committed key as the recorder: every build has the same signature.
    signingConfigs {
        create("personal") {
            storeFile = rootProject.file("app/personal.keystore")
            storePassword = "thaivocab"
            keyAlias = "thaivocab"
            keyPassword = "thaivocab"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false // the Onyx library uses reflection; keep all classes
            signingConfig = signingConfigs.getByName("personal")
        }
        debug {
            signingConfig = signingConfigs.getByName("personal")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        jniLibs.pickFirsts += "**/libc++_shared.so"
    }
}

dependencies {
    implementation(project(":core"))
    implementation("com.onyx.android.sdk:onyxsdk-pen:1.5.9")
    implementation("com.onyx.android.sdk:onyxsdk-base:1.8.10")
    implementation("com.onyx.android.sdk:onyxsdk-device:1.3.7")
    // The Onyx library calls hidden Android APIs. This library allows the calls on Android 9+.
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
```
If Gradle reports duplicate classes or a dependency that cannot be found, exclude the named group from the three Onyx lines (`{ exclude(group = "...") }`) and note the change in the commit message.

**Step 2: Write the failing test**

`boox/src/test/java/app/thaivocab/boox/ink/InkTest.kt`:
```kotlin
package app.thaivocab.boox.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InkTest {
    private fun stroke(vararg xy: Float) = Stroke(xy.toList().chunked(2).map { InkPoint(it[0], it[1]) })

    @Test fun `a stroke belongs to the zone where it starts`() {
        // Box height 100: the split line is at y = 64.
        assertEquals(Zone.THAI, zoneOf(stroke(10f, 60f, 10f, 90f), height = 100f))   // tail crosses the line
        assertEquals(Zone.ENGLISH, zoneOf(stroke(10f, 70f, 10f, 30f), height = 100f))
    }

    @Test fun `split puts each stroke in one list`() {
        val a = stroke(0f, 10f, 5f, 10f)
        val b = stroke(0f, 80f, 5f, 80f)
        val (thai, english) = splitZones(listOf(a, b), height = 100f)
        assertEquals(listOf(a), thai)
        assertEquals(listOf(b), english)
    }

    @Test fun `bounds add the margin and stay inside the box`() {
        val box = boundsOf(listOf(stroke(10f, 20f, 50f, 40f)), margin = 15f, width = 200f, height = 100f)!!
        assertEquals(Bounds(0f, 5f, 65f, 55f), box)
    }

    @Test fun `bounds of an empty list are null`() {
        assertNull(boundsOf(emptyList(), margin = 15f, width = 200f, height = 100f))
    }

    @Test fun `scale keeps small images and shrinks wide images`() {
        assertEquals(1f, scaleFor(width = 800f, maxWidth = 1000), 0.0001f)
        assertEquals(0.5f, scaleFor(width = 2000f, maxWidth = 1000), 0.0001f)
    }
}
```

**Step 3: Run the test and see it fail**

Run: `./gradlew --no-daemon :boox:testDebugUnitTest --tests "*InkTest"`
Expected: compilation fails with "Unresolved reference: Stroke".

**Step 4: Write `Ink.kt`**

```kotlin
package app.thaivocab.boox.ink

data class InkPoint(val x: Float, val y: Float)

/** One pen movement from pen-down to pen-up, in pixels of the writing box. */
data class Stroke(val points: List<InkPoint>)

enum class Zone { THAI, ENGLISH }

/** The split line between the two zones, as a part of the box height. Same value as the mockup. */
const val SPLIT = 0.64f

/** A stroke belongs to the zone where it starts, so a long Thai tail may cross the line. */
fun zoneOf(stroke: Stroke, height: Float): Zone =
    if (stroke.points.first().y > height * SPLIT) Zone.ENGLISH else Zone.THAI

/** Returns the strokes of the upper zone and the strokes of the lower zone. */
fun splitZones(strokes: List<Stroke>, height: Float): Pair<List<Stroke>, List<Stroke>> =
    strokes.filter { it.points.isNotEmpty() }.partition { zoneOf(it, height) == Zone.THAI }

data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/** The smallest rectangle around the ink plus [margin], limited to the box. Null without ink. */
fun boundsOf(strokes: List<Stroke>, margin: Float, width: Float, height: Float): Bounds? {
    val points = strokes.flatMap { it.points }
    if (points.isEmpty()) return null
    return Bounds(
        left = (points.minOf { it.x } - margin).coerceAtLeast(0f),
        top = (points.minOf { it.y } - margin).coerceAtLeast(0f),
        right = (points.maxOf { it.x } + margin).coerceAtMost(width),
        bottom = (points.maxOf { it.y } + margin).coerceAtMost(height),
    )
}

/** Factor that makes an image at most [maxWidth] pixels wide. Small images keep their size. */
fun scaleFor(width: Float, maxWidth: Int): Float = if (width <= maxWidth) 1f else maxWidth / width
```

**Step 5: Run the test and see it pass**

Run: `./gradlew --no-daemon :boox:testDebugUnitTest --tests "*InkTest"`
Expected: 5 tests pass. (The module still needs a manifest to build; if the build asks for one, do step 1 of Task 9 first.)

**Step 6: Commit**

```bash
git add -A && git commit -m "Add the boox module with ink geometry"
```

---

### Task 9: Tablet app shell, pen surface and PNG output

**Files:**
- Create: `boox/src/main/AndroidManifest.xml`
- Create: `boox/src/main/res/values/strings.xml`, `boox/src/main/res/values/themes.xml`, `boox/src/main/res/xml/file_paths.xml`
- Copy: launcher icon files from `app/src/main/res/` (`drawable/ic_launcher_*.xml`, all `mipmap-*` folders)
- Create: `boox/src/main/java/app/thaivocab/boox/BooxApp.kt`, `MainActivity.kt`
- Create: `boox/src/main/java/app/thaivocab/boox/ink/InkRenderer.kt`, `InkView.kt`

**Step 1: Manifest and resources**

`boox/src/main/AndroidManifest.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <!-- In-app update: open Android's installer for a downloaded new version. -->
    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />

    <application
        android:name=".BooxApp"
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:roundIcon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.ThaiVocabBoox">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:screenOrientation="portrait"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.files"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>
    </application>
</manifest>
```
`strings.xml`: `<string name="app_name">Thai Writing</string>`.
`themes.xml`: a style `Theme.ThaiVocabBoox` with parent `android:Theme.DeviceDefault.Light.NoActionBar`.
`file_paths.xml`: copy from `app/src/main/res/xml/file_paths.xml`.

**Step 2: `BooxApp.kt`**

```kotlin
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
```

**Step 3: `InkRenderer.kt`**

```kotlin
package app.thaivocab.boox.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import java.io.File

object InkRenderer {
    const val MAX_WIDTH = 1000
    const val MARGIN = 24f

    fun paint(width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    fun pathOf(stroke: Stroke) = Path().apply {
        val first = stroke.points.first()
        moveTo(first.x, first.y)
        // A single point is drawn as a dot.
        if (stroke.points.size == 1) lineTo(first.x + 0.1f, first.y)
        stroke.points.drop(1).forEach { lineTo(it.x, it.y) }
    }

    /**
     * Draws [strokes] as black ink on white, cropped to the ink, and writes a PNG file.
     * Returns false when [strokes] is empty (no file is written).
     */
    fun writePng(strokes: List<Stroke>, boxWidth: Float, boxHeight: Float, strokeWidth: Float, target: File): Boolean {
        val box = boundsOf(strokes, MARGIN, boxWidth, boxHeight) ?: return false
        val scale = scaleFor(box.width, MAX_WIDTH)
        val bitmap = Bitmap.createBitmap(
            (box.width * scale).toInt().coerceAtLeast(1), (box.height * scale).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            scale(scale, scale)
            translate(-box.left, -box.top)
            val paint = paint(strokeWidth)
            strokes.forEach { drawPath(pathOf(it), paint) }
        }
        target.parentFile?.mkdirs()
        target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return true
    }
}
```

**Step 4: `InkView.kt`**

The view collects strokes. On the Boox, the Onyx library draws the ink while the pen moves and delivers each finished stroke. On other devices, the view draws from normal touch events.
```kotlin
package app.thaivocab.boox.ink

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList

/** The writing box: an upper zone (Thai or phonetic) and a lower zone (English). */
@SuppressLint("ViewConstructor")
class InkView(context: Context, private val onInkChanged: (thaiInk: Boolean) -> Unit) :
    SurfaceView(context), SurfaceHolder.Callback {

    private val strokes = mutableListOf<Stroke>()
    private var current = mutableListOf<InkPoint>()
    private var touchHelper: TouchHelper? = null
    private var active = true
    val strokeWidth = 5f

    private val inkPaint = InkRenderer.paint(strokeWidth)
    private val linePaint = Paint().apply { color = Color.BLACK; strokeWidth = 3f }
    private val basePaint = Paint().apply {
        color = Color.DKGRAY; strokeWidth = 1.5f; style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 22f }

    init {
        holder.addCallback(this)
    }

    /** A copy of the strokes, and the size of the box they were written in. */
    fun snapshot(): List<Stroke> = strokes.toList()

    fun clear() {
        strokes.clear()
        redraw()
        onInkChanged(false)
    }

    /** Switches pen input off while a window covers the writing box. */
    fun setActive(value: Boolean) {
        active = value
        touchHelper?.setRawDrawingEnabled(value)
        if (!value) redraw()
    }

    private fun add(stroke: Stroke) {
        if (stroke.points.isEmpty()) return
        strokes += stroke
        onInkChanged(splitZones(strokes, height.toFloat()).first.isNotEmpty())
    }

    // ---- Onyx pen library ----

    private val callback = object : RawInputCallback() {
        override fun onBeginRawDrawing(b: Boolean, p: TouchPoint?) = Unit
        override fun onEndRawDrawing(b: Boolean, p: TouchPoint?) = Unit
        override fun onRawDrawingTouchPointMoveReceived(p: TouchPoint?) = Unit
        override fun onRawDrawingTouchPointListReceived(list: TouchPointList?) {
            val points = list?.points?.map { InkPoint(it.x, it.y) } ?: return
            post { add(Stroke(points)) }
        }
        override fun onBeginRawErasing(b: Boolean, p: TouchPoint?) = Unit
        override fun onEndRawErasing(b: Boolean, p: TouchPoint?) = Unit
        override fun onRawErasingTouchPointMoveReceived(p: TouchPoint?) = Unit
        override fun onRawErasingTouchPointListReceived(list: TouchPointList?) = Unit
    }

    private fun startOnyx() {
        touchHelper = try {
            TouchHelper.create(this, callback).apply {
                setStrokeWidth(strokeWidth)
                setStrokeStyle(TouchHelper.STROKE_STYLE_PENCIL)
                setLimitRect(Rect(0, 0, width, height), emptyList())
                openRawDrawing()
                setRawDrawingEnabled(active)
            }
        } catch (e: Throwable) {
            null // not a Boox device: onTouchEvent draws the ink
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        redraw()
        touchHelper?.closeRawDrawing()
        startOnyx()
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        touchHelper?.closeRawDrawing()
        touchHelper = null
    }

    // ---- Normal touch events (other devices, emulator) ----

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (touchHelper != null || !active) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> current = mutableListOf(InkPoint(event.x, event.y))
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.historySize) current += InkPoint(event.getHistoricalX(i), event.getHistoricalY(i))
                current += InkPoint(event.x, event.y)
                redraw(Stroke(current.toList()))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                add(Stroke(current.toList()))
                current = mutableListOf()
                redraw()
            }
        }
        return true
    }

    /** Draws the zone lines, the labels and all strokes on the surface. */
    private fun redraw(extra: Stroke? = null) {
        val enabled = touchHelper != null && active
        if (enabled) touchHelper?.setRawDrawingEnabled(false)
        val canvas = holder.lockCanvas() ?: return
        try {
            canvas.drawColor(Color.WHITE)
            val split = height * SPLIT
            canvas.drawLine(0f, split, width.toFloat(), split, linePaint)
            canvas.drawLine(width * 0.04f, height * 0.44f, width * 0.96f, height * 0.44f, basePaint)
            canvas.drawText("THAI OR PHONETIC", 14f, 30f, labelPaint)
            canvas.drawText("ENGLISH, OPTIONAL", 14f, split + 32f, labelPaint)
            (strokes + listOfNotNull(extra)).forEach { canvas.drawPath(InkRenderer.pathOf(it), inkPaint) }
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
        if (enabled) touchHelper?.setRawDrawingEnabled(true)
    }
}
```
The class and package names of the Onyx library come from unofficial documentation. If the compiler reports an unresolved name, open the downloaded `onyxsdk-pen-1.5.9` classes in Android Studio and correct the import. Keep the behaviour: collect each finished stroke as a list of points.

**Step 5: `MainActivity.kt` (temporary test screen)**

```kotlin
package app.thaivocab.boox

import android.os.Bundle
import androidx.activity.ComponentActivity
import app.thaivocab.boox.ink.InkView

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(InkView(this) {})
    }
}
```

**Step 6: Build and try on the tablet**

```bash
./gradlew --no-daemon :boox:assembleDebug
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" -s AAFF665D install -r boox/build/outputs/apk/debug/boox-debug.apk
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" -s AAFF665D shell am start -n app.thaivocab.boox/.MainActivity
```
Ask Merlin to write in the box with the pen. Expected: the ink appears under the pen tip without a visible delay. Then check that the app received the strokes:
```bash
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" -s AAFF665D exec-out screencap -p > "$TEMP/boox-ink.png"
```
Read the screenshot. If the ink is shifted from the pen position, the points are in screen coordinates: subtract the view position (`getLocationOnScreen`) in the callback.

**Step 7: Commit**

```bash
git add -A && git commit -m "Boox app shell with pen surface and PNG output"
```

---

### Task 10: View model and tablet screen

**Files:**
- Create: `boox/src/main/java/app/thaivocab/boox/ui/BooxViewModel.kt`
- Create: `boox/src/main/java/app/thaivocab/boox/ui/BooxScreen.kt`, `EditWindow.kt`, `EinkTheme.kt`, `Previews.kt`
- Create: `boox/src/test/java/app/thaivocab/boox/ScreenshotTest.kt`
- Modify: `boox/src/main/java/app/thaivocab/boox/MainActivity.kt`
- Reference: `app/src/main/java/app/thaivocab/recorder/ui/MainScreen.kt` (`SyncBar`, `ContextBar`, `UpdateBanner`), `Dialogs.kt` (`EditDialog`, `TypeDialog`), `MainViewModel.kt`

**Step 1: `BooxViewModel.kt`**

```kotlin
package app.thaivocab.boox.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.thaivocab.boox.ink.InkRenderer
import app.thaivocab.boox.ink.Stroke
import app.thaivocab.boox.ink.splitZones
import app.thaivocab.core.data.CaptureContext
import app.thaivocab.core.data.ContextCatalog
import app.thaivocab.core.data.ContextSetting
import app.thaivocab.core.data.CustomContext
import app.thaivocab.core.data.Transcription
import app.thaivocab.core.data.VocabEntry
import app.thaivocab.core.data.VocabStore
import app.thaivocab.core.sync.Connection
import app.thaivocab.core.sync.SupabaseConfig
import app.thaivocab.core.sync.SyncWorker
import app.thaivocab.core.update.Updater
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class BooxViewModel(app: Application) : AndroidViewModel(app) {
    private val store = VocabStore.get(app)
    private val contextSetting = ContextSetting(app)
    private val catalog = ContextCatalog.get(app)
    val connection = Connection(app)

    val entries: StateFlow<List<VocabEntry>> = store.entries
    val supabase: StateFlow<SupabaseConfig.State> = SupabaseConfig.get(app).state
    val captureContext: StateFlow<CaptureContext> = contextSetting.current
    val contextOptions: StateFlow<List<CaptureContext>> = catalog.selectable
    val customContexts: StateFlow<List<CustomContext>> = catalog.customs

    init {
        SyncWorker.schedule(app)
    }

    private fun sync() = SyncWorker.schedule(getApplication())

    /**
     * Saves what the user wrote. [phonetic] is true for the button "Next: Phonetic".
     * Returns false when the upper zone is empty (nothing is saved).
     */
    fun saveInk(strokes: List<Stroke>, boxWidth: Float, boxHeight: Float, strokeWidth: Float, phonetic: Boolean): Boolean {
        val (thai, english) = splitZones(strokes, boxHeight)
        val id = UUID.randomUUID().toString()
        val dir = File(getApplication<Application>().filesDir, "handwriting")
        val image = File(dir, "$id.png")
        if (!InkRenderer.writePng(thai, boxWidth, boxHeight, strokeWidth, image)) return false
        val englishImage = File(dir, "$id-en.png")
        val hasEnglish = InkRenderer.writePng(english, boxWidth, boxHeight, strokeWidth, englishImage)
        val now = System.currentTimeMillis()
        store.insert(
            VocabEntry(
                id = id, thai = null, alternatives = emptyList(), notes = null, audioFile = null,
                capturedAt = now, updatedAt = now, context = contextSetting.current.value,
                imageFile = image.absolutePath,
                englishImageFile = if (hasEnglish) englishImage.absolutePath else null,
                imagesUploaded = false,
                writtenScript = if (phonetic) "phonetic" else "thai",
                transcription = Transcription.PENDING, dirty = true, audioUploaded = false, deleted = false,
            )
        )
        contextSetting.touch()
        sync()
        return true
    }

    fun addTyped(thai: String, notes: String?) {
        val now = System.currentTimeMillis()
        store.insert(
            VocabEntry(
                id = UUID.randomUUID().toString(), thai = thai.trim(), alternatives = emptyList(),
                notes = notes?.trim()?.ifEmpty { null }, audioFile = null, capturedAt = now, updatedAt = now,
                transcription = Transcription.MANUAL, dirty = true, audioUploaded = false, deleted = false,
                context = contextSetting.current.value,
            )
        )
        contextSetting.touch()
        sync()
    }

    fun saveEdit(entry: VocabEntry, thai: String, notes: String, context: CaptureContext) {
        val newThai = thai.trim().ifEmpty { null }
        val alts = (listOfNotNull(entry.thai) + entry.alternatives).filter { it != newThai }.distinct()
        store.update(
            entry.copy(
                thai = newThai, alternatives = alts, notes = notes.trim().ifEmpty { null }, context = context,
                transcription = if (newThai != entry.thai) Transcription.MANUAL else entry.transcription,
                english = if (newThai != entry.thai) null else entry.english,
                romanization = if (newThai != entry.thai) null else entry.romanization,
            )
        )
        sync()
    }

    /** The user pressed the wrong Next button: change the script type and read the image again. */
    fun readAgainAsOtherType(entry: VocabEntry) {
        if (entry.imageFile == null) return
        store.update(
            entry.copy(
                writtenScript = if (entry.writtenScript == "phonetic") "thai" else "phonetic",
                thai = null, alternatives = emptyList(), english = null, romanization = null,
                transcription = Transcription.PENDING, transcriptionError = null,
            )
        )
        sync()
    }

    /** Runs the reading step again, for an entry where it failed. */
    fun retryReading(entry: VocabEntry) {
        if (entry.imageFile == null) return
        store.update(entry.copy(transcription = Transcription.PENDING, transcriptionError = null))
        sync()
    }

    /** Removes the handwriting images from the word; the word itself stays. */
    fun removeImage(entry: VocabEntry) {
        entry.imageFile?.let { File(it).delete() }
        entry.englishImageFile?.let { File(it).delete() }
        store.update(entry.copy(imageFile = null, englishImageFile = null, imagesUploaded = true, imageCleared = true))
        sync()
    }

    fun delete(entry: VocabEntry) {
        store.markDeleted(entry.id)
        sync()
    }

    fun setContext(value: CaptureContext) = contextSetting.set(value)
    fun recheckContext() = contextSetting.recheck()
    fun addContext(label: String) { if (label.isNotBlank()) { catalog.add(label); sync() } }
    fun renameContext(key: String, label: String) { if (label.isNotBlank()) { catalog.rename(key, label); afterContextChange() } }
    fun removeContext(key: String) { catalog.remove(key); afterContextChange() }
    private fun afterContextChange() { store.refresh(); contextSetting.recheck(); sync() }

    // In-app update from the "latest" GitHub release (same logic as the recorder).
    private val updater = Updater(app)
    private val _update = MutableStateFlow<Updater.Available?>(null)
    val update: StateFlow<Updater.Available?> = _update.asStateFlow()
    private val _updateProgress = MutableStateFlow<Float?>(null)
    val updateProgress: StateFlow<Float?> = _updateProgress.asStateFlow()
    private var lastUpdateCheck = 0L

    fun checkForUpdate() {
        val now = System.currentTimeMillis()
        if (now - lastUpdateCheck < 10 * 60_000) return
        lastUpdateCheck = now
        viewModelScope.launch { _update.value = updater.check() }
    }

    fun installUpdate() {
        if (_updateProgress.value != null) return
        _updateProgress.value = 0f
        viewModelScope.launch {
            try {
                updater.install(updater.download { p -> _updateProgress.value = p })
            } catch (e: Exception) {
                // The banner stays, so the user can tap again.
            } finally {
                _updateProgress.value = null
            }
        }
    }
}
```
`readAgainAsOtherType` uploads the row with `thai = null` and `transcription_status = 'pending'`. The `read` step in Task 6 accepts a row in that state and fills `thai` again.

**Step 2: `EinkTheme.kt`**

```kotlin
package app.thaivocab.boox.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

private val Ink = Color(0xFF000000)
private val Paper = Color(0xFFFFFFFF)
private val Grey = Color(0xFF555555)

/** Black and white only, and no ripple animation: the screen is e-ink. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EinkTheme(content: @Composable () -> Unit) {
    val colors = lightColorScheme(
        primary = Ink, onPrimary = Paper, secondary = Ink, onSecondary = Paper,
        background = Paper, onBackground = Ink, surface = Paper, onSurface = Ink,
        surfaceVariant = Paper, onSurfaceVariant = Grey, outline = Ink, error = Ink, onError = Paper,
        secondaryContainer = Ink, onSecondaryContainer = Paper, surfaceContainerHigh = Paper,
    )
    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(LocalRippleConfiguration provides null, content = content)
    }
}
```

**Step 3: `BooxScreen.kt`**

Write two composables, following the structure of `MainScreen` / `MainContent` in the recorder:

- `BooxScreen(vm: BooxViewModel = viewModel())` collects the state flows, holds the window state (`editing`, `typing`, `showSetup`, `showLabels`, `labelAction`), creates the `InkView` with `AndroidView`, and calls `BooxContent`. When any window is open, call `inkView.setActive(false)`; when all are closed, call `inkView.setActive(true)`. Without this call, the pen writes through the window.
- `BooxContent(...)` is stateless and takes the pad as a parameter `pad: @Composable () -> Unit`, so previews and screenshots can pass a plain white `Box`.

Layout of `BooxContent`, top to bottom in one `Column(Modifier.fillMaxSize())`:

| Part | Composable | Details |
|---|---|---|
| Update banner | copy `UpdateBanner` from the recorder | only when `update != null` |
| Status line | copy `SyncBar` from the recorder | 1 dp black bottom border |
| List | `LazyColumn(Modifier.weight(1f))` of `EntryRow` | newest first (order of `store.entries`) |
| Context labels | copy `ContextBar` from the recorder | selected chip: black with white text |
| Writing box | `Box(Modifier.fillMaxWidth().fillMaxHeight(0.39f).padding(horizontal = 16.dp).border(2.dp, Color.Black)) { pad() }` | the fraction applies to the screen height: compute the height with `BoxWithConstraints` around the `Column` |
| Buttons | `Row`: `OutlinedButton("Clear")`, `OutlinedIconButton(Keyboard)`, `Button("Next: Phonetic", Modifier.weight(1f))`, `Button("Next: ไทย", Modifier.weight(1f))` | both Next buttons `enabled = thaiInk`; height 56 dp |

`EntryRow(entry, onClick)`: a `Row` with `clickable(onClick)` and a 1 dp bottom border.
- Left: the handwriting image, 220 × 100 dp, with a dashed 1 dp border. Load the bitmap with `remember(entry.imageFile) { entry.imageFile?.let { BitmapFactory.decodeFile(it) } }` and show it with `Image(bitmap.asImageBitmap(), contentScale = ContentScale.Fit)`. Without an image, show an empty box of the same size.
- Right, a `Column`:
  - `Transcription.PENDING` and the entry is not uploaded yet (`entry.dirty`): text "Waiting to upload".
  - `Transcription.PENDING` otherwise: text "Getting Thai text…".
  - `Transcription.FAILED`: text "Could not read. Tap to type or retry."
  - Otherwise: the Thai text at 30 sp. When `entry.transcription == Transcription.GUESSED`, add a bordered label "GUESSED" after the text.
  - Then `romanization · english` at 16 sp, or "Getting meaning…" when `entry.thai != null && entry.english == null`.
  - When `entry.writtenScript == "phonetic" && entry.writtenText != null`: "You wrote: {writtenText}".
  - When `entry.writtenEnglish != null`: "Your translation: {writtenEnglish}".
  - Last line at 13 sp: time (`HH:mm` today, else `d MMM`) and the context label.

Button actions in `BooxScreen`:
```kotlin
fun next(phonetic: Boolean) {
    val view = inkView ?: return
    if (vm.saveInk(view.snapshot(), view.width.toFloat(), view.height.toFloat(), view.strokeWidth, phonetic)) view.clear()
}
```
Call `vm.recheckContext()` and `vm.checkForUpdate()` in a `LifecycleResumeEffect`, as the recorder does.

**Step 4: `EditWindow.kt`**

Copy `EditDialog` and `TypeDialog` from the recorder's `Dialogs.kt` into this file and change `EditDialog`:
- Remove the play button and the two audio actions (`onPlay`, `onRemoveOwnRecording`, `onRemoveNativeClip`).
- Show the handwriting image at the top (full width, 120 dp high) when `entry.imageFile != null`, and the English image under it when `entry.englishImageFile != null`.
- Add the parameters `onReadAgain: () -> Unit`, `onRetry: () -> Unit` and `onRemoveImage: () -> Unit`.
- When `entry.imageFile != null`, show the button `Read again as Thai script` (when `entry.writtenScript == "phonetic"`) or `Read again as phonetic` (otherwise), and the button `Remove handwriting image` with the same confirmation step that the recorder uses for "Remove my recording".
- When `entry.transcription == Transcription.FAILED && entry.imageFile != null`, show `Try reading again` (calls `onRetry`).
- Keep: Thai text field, "Other possibilities (tap to use)", notes, context chips, Delete with confirmation, Cancel, Save.

Wire the window in `BooxScreen` to `vm.saveEdit`, `vm.readAgainAsOtherType`, `vm.retryReading`, `vm.removeImage` and `vm.delete`. Use `SetupDialog`, `LabelsDialog` and `LabelActionDialog` from `app.thaivocab.core.ui`, wired as in the recorder's `MainScreen.kt` (lines 187–204).

**Step 5: `MainActivity.kt`**

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { EinkTheme { BooxScreen() } }
    }
}
```

**Step 6: Previews and screenshot test**

`Previews.kt`: one `@Preview(widthDp = 936, heightDp = 1248)` that calls `BooxContent` with four sample entries: Thai done, phonetic guessed with `writtenText` and `writtenEnglish`, pending, failed. Pass `pad = { Box(Modifier.fillMaxSize().background(Color.White)) }`.

`boox/src/test/java/app/thaivocab/boox/ScreenshotTest.kt`: copy the recorder's `ScreenshotTest.kt`, set the device to `DeviceConfig(screenWidth = 1404, screenHeight = 1872, xdpi = 240, ydpi = 240, density = Density.create(240))`, and take one snapshot of the preview composable.

Run: `./gradlew --no-daemon :boox:recordPaparazziDebug`
Expected: `BUILD SUCCESSFUL`. Read the PNG in `boox/src/test/snapshots/images/` and compare the layout with the mockup. Add `boox/src/test/snapshots/` to `.gitignore`.

**Step 7: Check the edit rule from `CLAUDE.md`**

List every thing that the user can create in the tablet app (a word, a handwriting image, a label, the connection) and confirm that a tap on each one opens a way to edit and to delete it.

**Step 8: Build and commit**

Run: `./gradlew --no-daemon :boox:assembleDebug :boox:testDebugUnitTest`
```bash
git add -A && git commit -m "Boox app: tablet screen, edit window and view model"
```

---

### Task 11: GitHub build, README and agent notes

**Files:**
- Modify: `.github/workflows/build.yml`
- Modify: `README.md`, `CLAUDE.md`

**Step 1: Build each app only when its files change**

Replace the job `build` with three jobs:
```yaml
jobs:
  changes:
    runs-on: ubuntu-latest
    outputs:
      app: ${{ steps.filter.outputs.app }}
      boox: ${{ steps.filter.outputs.boox }}
    steps:
      - uses: actions/checkout@v5
      - uses: dorny/paths-filter@v3
        id: filter
        with:
          filters: |
            shared: &shared
              - 'core/**'
              - 'gradle/**'
              - '*.gradle.kts'
              - 'gradle.properties'
              - '.github/workflows/**'
            app:
              - *shared
              - 'app/**'
            boox:
              - *shared
              - 'boox/**'
```
- Job `recorder`: `needs: changes`, `if: needs.changes.outputs.app == 'true' || github.event_name == 'workflow_dispatch'`. Keep all steps of the old job and change the Gradle tasks to `:app:assembleRelease` and `:app:recordPaparazziDebug`.
- Job `boox`: `needs: changes`, `if: needs.changes.outputs.boox == 'true' || github.event_name == 'workflow_dispatch'`. Same steps with `:boox:assembleRelease` and `:boox:recordPaparazziDebug`, APK path `boox/build/outputs/apk/release/boox-release.apk`, file names `thai-vocab-boox.apk` and `version-boox.json`, artifact names `thai-vocab-boox-apk` and `screenshots-boox`, screenshot path `boox/src/test/snapshots/images/*.png`.

`softprops/action-gh-release` with `overwrite_files: true` adds the files of one job to the release and keeps the files of the other job.

**Step 2: README**

- In "Parts of the project", add rows for `core/` (shared code), `boox/` (tablet app) and `supabase/functions/process/` (step `read`).
- Add a section "Tablet app (Boox)" after "How the app works": how to install (`thai-vocab-boox.apk` from the latest release), how to enable USB debugging on the Boox (Apps tab → top-right menu → Manage apps → USB Debug Mode), the two zones, the two Next buttons, the status `guessed`, and the edit window.
- In the table of `vocab_items`, add the five new columns and the source `boox_handwriting`. Add `guessed` to `transcription_status`.
- In "Build on your own computer", document `./gradlew :app:assembleRelease` and `./gradlew :boox:assembleRelease`.

Write in plain international English and describe the software in mechanical terms.

**Step 3: `CLAUDE.md`**

Replace the first bullet with:
```
- Two Android apps (Kotlin, Jetpack Compose): the phone recorder in `app/` and the Boox
  tablet app in `boox/`. Shared code (store, sync, updater, shared dialogs) is in `core/`;
  a change there affects both apps, so build both. Database and server functions for
  Supabase are in `supabase/`. Overview and set-up: `README.md`.
- Gradle needs JDK 17 or newer: `export JAVA_HOME="$HOME/.jdks/jbr-21.0.11"`.
```

**Step 4: Commit**

```bash
git add -A && git commit -m "Build both apps on GitHub; document the tablet app"
```

---

### Task 12: Test on the devices

**Files:** none, unless a test finds a defect.

**Step 1: Install the tablet app**

```bash
./gradlew --no-daemon :boox:assembleRelease
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" -s AAFF665D install -r boox/build/outputs/apk/release/boox-release.apk
```

**Step 2: Tests with Merlin on the tablet**

| Test | Expected |
|---|---|
| Connect with the Supabase account | Status line shows "All saved to database"; the list shows the words from the phone |
| Write a Thai word, press `Next: ไทย` | Box is empty at once; row shows the image, then the Thai text, then romanization and meaning |
| Write a phonetic word, press `Next: Phonetic` | Row shows the Thai guess with GUESSED and "You wrote: …" |
| Write a word and an English translation in the lower zone | Row also shows "Your translation: …" |
| Switch Wi-Fi off, write three words, switch Wi-Fi on | Rows show "Waiting to upload", then fill in without any action |
| Tap a row, press "Read again as phonetic" | Row returns to "Getting Thai text…" and then shows the new result |
| Tap a row, correct the Thai text, save | New text is shown; the meaning is fetched again |
| Tap a row, "Remove handwriting image" | Row stays, without the image |
| Tap a row, Delete | Row disappears; `deleted_at` is set in the database |
| Open a window, then touch the pen on the writing box area | Ink does not appear through the window |

Check the rows in the Supabase table editor: `source = 'boox_handwriting'`, `image_path`, `written_script`, `written_text` and `transcription_status` have the expected values.

**Step 3: Recorder regression on the phone**

Build `:app:assembleRelease` and install it on the Pixel 7a (ask Merlin to enable USB debugging, or let Merlin install the file). Record one word. Expected: Thai text, romanization and meaning appear as before, and the rows from the tablet are in the list.

**Step 4: Finish**

Use the skill `superpowers:verification-before-completion`, then `superpowers:finishing-a-development-branch`. Ask Merlin before any push.
