# Thai Vocab Recorder

An Android app to capture Thai words and phrases that you hear or see in daily life.

**How to use it:** open the app, hold the red button, say the word, release.
The app saves the audio clip, converts it to Thai text with Google speech recognition,
and uploads both to a shared database. The other app (a drill app) can read the same
database later.

```
 ┌──────────────────────────────┐
 │ ☁ All saved to database      │  ← tap here once to connect the database
 │ [Field work] Lingping 🎓Boom 🎓Ae │  ← where you are (lesson = teacher)
 ├──────────────────────────────┤
 │ ตลาดน้ำ                    ▶ │  ← tap a row to correct the text or add a note
 │ ตลาดนํ้า · ตลาด น้ำ          │     (other recognition candidates)
 │ 1 Nov, 14:02                 │
 │ ขอบคุณครับ                 ▶ │
 ├──────────────────────────────┤
 │            ( 🎤 )        ⌨   │  ← hold to record   /  type a word you read
 │             Hold             │
 └──────────────────────────────┘
```

## Parts of the project

| Part | Location | Purpose |
|---|---|---|
| Android app | `app/` | Kotlin + Jetpack Compose. Works offline; uploads when the phone has a network connection. |
| Database schema | `supabase/migrations/` | Tables, security rules and audio storage for Supabase (hosted PostgreSQL). One SQL file per change, applied in name order. |
| Build | `.github/workflows/build.yml` | GitHub Actions builds the APK on every push. |

## Set-up (one time, about 10 minutes)

### 1. Create the database

1. Create a free account and a new project at <https://supabase.com>.
   Choose a region near you (for example Singapore).
2. Create the tables. Use **one** of these two ways:
   * **Automatic (GitHub integration):** in Supabase, open
     **Project Settings → Integrations → GitHub**, connect this repository, set the
     Supabase directory to `supabase`, and switch on **Deploy to production** for the
     branch `main`. When this code is merged into `main`, Supabase runs the files in
     `supabase/migrations/`. Later database changes arrive the same way.
   * **Manual:** open **SQL Editor → New query**, paste the content of each file in
     `supabase/migrations/` (oldest first), and click **Run**.
3. Open **Authentication → Users → Add user → Create new user**.
   Enter your email and a password, and select **Auto Confirm User**.
   The app (and later the drill app) signs in with this account.
4. Open **Project Settings → API Keys** (or **Settings → API** in older projects) and note:
   * the **Project URL**, for example `https://abcdefgh.supabase.co`
   * the **publishable key** (`sb_publishable_…`) or, in older projects, the **anon public** key.

   Do **not** use the secret / service_role key in the app.

### 2. Install the app on the Pixel

1. On the phone, open the GitHub page of this repository →
   **Releases → Latest build** and download `thai-vocab-recorder.apk`.
   (Builds of other branches are available under **Actions → Build APK → Artifacts**
   as a zip file that contains the APK.)
2. Open the downloaded file. Android asks you to allow installation from this source
   (browser or Files app) one time. Allow it, then install.
3. New versions install over the old version. Your local entries are kept.
   **Do not uninstall first**: that deletes words that are not uploaded yet.
4. Later updates: when a newer build exists, the app shows a banner
   **"New version (build N) – tap to install"**. One tap downloads it and opens
   Android's installer. The first time, Android asks you to allow installs from this app.

### 3. Connect the app

Open the app and tap the line at the top (**Not connected to database**). Enter the
Project URL, the publishable key, and the email and password from step 1.3.
The app stores only a login token; the password is not saved.

## How the app works

* **Capture:** the microphone records 16 kHz mono WAV while you hold the button
  (maximum 60 seconds). Clips shorter than 0.4 seconds are ignored.
* **Context:** the buttons at the top select where the word comes from: *Field work*
  (default), *Just wondering* (looked up out of curiosity), *Lingping* (club), or a
  lesson with *Kru Boom* or *Kru Ae*. The choice is saved with
  each entry. It returns to *Field work* after 4 hours without a recording.
* **Thai text and meaning:** after upload, the app calls the Supabase Edge Function
  `process` (`supabase/functions/process/`). For a new clip, the function sends the audio
  to ElevenLabs speech-to-text (Thai). Then it sends the Thai text to Gemini, which returns
  the standard spelling, romanization, English meaning, word/phrase and part of speech.
  The function saves all of it in the database and returns it to the phone. A corrected
  spelling replaces `thai`; the original transcript stays in `thai_alternatives`. Rows
  with `enrichment_status = 'reviewed'` are never changed by the AI.
  Keys are stored only in Supabase (**Edge Functions → Secrets**): `ELEVENLABS_API_KEY`
  and `GEMINI_API_KEY`. Errors are saved in `transcription_error` / `enrichment_error`.
* **Waveform and playback:** bars show the microphone level while you record. During
  playback, a ring around the play button shows the progress; tap again to stop.
* **Offline first:** each entry is first saved in a local SQLite database on the phone.
  A background job (Android WorkManager) uploads the entries when a network is available
  and retries automatically after an error.
* **Download:** at each sync (and each time you open the app), the app downloads the
  newest 500 words and their audio from the database. So the list also shows changes
  made in the Supabase dashboard (for example a corrected spelling, an English meaning),
  and your history comes back after a reinstall. Local changes that are not uploaded
  yet always win. A word deleted in the dashboard also disappears from the phone.
* **Edits and deletes** on the phone are also uploaded. A delete sets `deleted_at` in the
  database (soft delete), so other apps can ignore the item.

## The database, for other apps

Table `vocab_items` (see `supabase/migrations/` for all columns). The numbers refer
to the hand-written specification (171.0 – 171.11).

| Column | Spec | Filled by | Content |
|---|---|---|---|
| `id` | 11 | input app | UUID, created on the phone |
| `captured_at` | 1 | input app | date and time (UTC) |
| `context`, `teacher` | 0 | recorder | `field` (default), `wondering`, `lingping_club`, or `lesson` with teacher `Boom` / `Ae` |
| `source` | 140 | each input app | `field_recorder`, later `lesson_recorder`, `pickup_thai`, … |
| `thai`, `thai_alternatives` | 2 | recorder | Thai script from speech recognition, or typed |
| `audio_path` | 7 | recorder | original recording in the Storage bucket `vocab-audio` (always kept) |
| `notes` | – | recorder | free text |
| `romanization` | 3 | later | |
| `english` | 4 | later | |
| `entry_type` | 5 | later | `word` or `phrase` |
| `part_of_speech` | 6 | later | `noun`, `verb`, `adjective`, `preposition`, `article`, `classifier`, `particle`, … |
| `tts_audio_path` | 8 | later | computer voice (text-to-speech) for the drill app |
| `quality`, `duplicate_of` | 172, 173 | admin | `ok`, `junk`, `mispronounced`, `duplicate` |
| `transcription_status` | – | recorder | `pending`, `done`, `failed`, `manual` |
| `enrichment_status` | – | enrichment step | `new` → `enriched` → `reviewed` |
| `updated_at`, `deleted_at` | – | input app | `deleted_at` is set when you delete on the phone |

Table `quiz_events` (spec 9 and 10) is for the drill app: one row each time a
question is asked, with `asked_at`, `answered_at` and `correct`. The recorder never
writes to it. The view `vocab_stats` gives `times_asked` and `times_correct` per item,
and the view `quiz_weekly` gives the results per week.

View `vocab_active` contains only the items that are not deleted and have `quality = 'ok'`.

Other apps can use the database with any Supabase client library
(JavaScript, Python, Dart/Flutter, Kotlin, Swift) or with plain HTTP (REST API).
They sign in with the same email and password; Row Level Security makes sure that each
user sees only their own items. To play an audio clip, create a signed URL for
`audio_path` in the bucket `vocab-audio`.

Rule for every new input source: write to `vocab_items`, set `source` to a new name,
and use `enrichment_status = 'new'` so that the enrichment step finds the new items.

## See the screens without a phone

**Android Studio (fastest):**

1. Install Android Studio from <https://developer.android.com/studio> and open this
   folder (**File → Open**). The first Gradle sync downloads the Android SDK and takes
   some minutes.
2. Open `app/src/main/java/app/thaivocab/recorder/ui/Previews.kt` and click **Split**
   (top right of the editor). The screens appear with sample Thai entries: list,
   dark mode, recording, first start, and the edit dialog.
3. Change colours, sizes or text in `MainScreen.kt`, `Dialogs.kt` or `Theme.kt`.
   The previews update automatically.

**Screenshots from GitHub:** every build also saves PNG screenshots of the main
screens under **Actions → Build APK → (run) → Artifacts → screenshots**.

## Build on your own computer

Open the project in Android Studio, or run:

```bash
./gradlew assembleRelease
```

The APK is in `app/build/outputs/apk/release/`.

The signing key `app/personal.keystore` is in the repository on purpose: all builds then
have the same signature, so a new APK installs over the old one. This is acceptable for
a personal app that is not published in the Play Store.
