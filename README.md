# Thai Vocab Recorder

An Android app to capture Thai words and phrases that you hear or see in daily life.

**How to use it:** open the app, hold the red button, say the word, release.
The app saves the audio clip, converts it to Thai text with Google speech recognition,
and uploads both to a shared database. The other app (a drill app) can read the same
database later.

```
 ┌──────────────────────────────┐
 │ ☁ All saved to database      │  ← tap here once to connect the database
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
| Database schema | `supabase/schema.sql` | Tables, security rules and audio storage for Supabase (hosted PostgreSQL). |
| Build | `.github/workflows/build.yml` | GitHub Actions builds the APK on every push. |

## Set-up (one time, about 10 minutes)

### 1. Create the database

1. Create a free account and a new project at <https://supabase.com>.
   Choose a region near you (for example Singapore).
2. In the project, open **SQL Editor → New query**, paste the full content of
   `supabase/schema.sql`, and click **Run**.
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

### 3. Connect the app

Open the app and tap the line at the top (**Not connected to database**). Enter the
Project URL, the publishable key, and the email and password from step 1.3.
The app stores only a login token; the password is not saved.

## How the app works

* **Capture:** the microphone records 16 kHz mono WAV while you hold the button
  (maximum 60 seconds). Clips shorter than 0.4 seconds are ignored.
* **Thai text:** after you release the button, the clip goes to the Android speech
  recogniser with the language `th-TH`. The best result becomes the Thai text; up to
  four other results are kept as alternatives. If recognition fails (for example,
  no network), the entry keeps the audio and shows *(no text – tap to type)*.
* **Offline first:** each entry is first saved in a local SQLite database on the phone.
  A background job (Android WorkManager) uploads the entries when a network is available
  and retries automatically after an error.
* **Edits and deletes** on the phone are also uploaded. A delete sets `deleted_at` in the
  database (soft delete), so other apps can ignore the item.

## The database, for other apps

Table `vocab_items` (see `supabase/schema.sql` for all columns):

| Column | Filled by | Content |
|---|---|---|
| `id` | input app | UUID, created on the phone |
| `thai`, `thai_alternatives` | recorder | Thai script from speech recognition, or typed |
| `notes` | recorder | free text |
| `audio_path` | recorder | `<user id>/<item id>.wav` in the Storage bucket `vocab-audio` |
| `meaning`, `romanization`, `part_of_speech`, `example`, `tags` | later | for an enrichment step (by hand or an AI job) |
| `source` | each input app | `field_recorder`, `import`, … |
| `transcription_status` | recorder | `pending`, `done`, `failed`, `manual` |
| `enrichment_status` | enrichment step | `new` → `enriched` → `reviewed` |
| `captured_at`, `updated_at`, `deleted_at` | input app | times (UTC) |

Table `review_state` is prepared for the drill app: one row per item with the
spaced-repetition values (`due_at`, `interval_days`, `ease`, `repetitions`, `lapses`).
The recorder never writes to it.

View `vocab_active` contains only the items where `deleted_at` is empty.

Other apps can use the database with any Supabase client library
(JavaScript, Python, Dart/Flutter, Kotlin, Swift) or with plain HTTP (REST API).
They sign in with the same email and password; Row Level Security makes sure that each
user sees only their own items. To play an audio clip, create a signed URL for
`audio_path` in the bucket `vocab-audio`.

Rule for every new input source: write to `vocab_items`, set `source` to a new name,
and use `enrichment_status = 'new'` so that the enrichment step finds the new items.

## Build on your own computer

Open the project in Android Studio, or run:

```bash
./gradlew assembleRelease
```

The APK is in `app/build/outputs/apk/release/`.

The signing key `app/personal.keystore` is in the repository on purpose: all builds then
have the same signature, so a new APK installs over the old one. This is acceptable for
a personal app that is not published in the Play Store.
