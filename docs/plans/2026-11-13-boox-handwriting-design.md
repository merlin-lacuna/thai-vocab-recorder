# Boox handwriting capture – design

Date: 2026-11-13. Status: approved in discussion, not built yet.
Interactive mockup of the screen: <https://claude.ai/artifact/ATEWg4FJbKnYUxAgeD2XNv>

## Purpose

A second Android app for the Boox Note Air2 Plus tablet. During a Thai lesson, the user
writes a word or a short phrase with the pen. The app saves the handwriting as a new row
in the same `vocab_items` table that the phone recorder uses. Claude reads the
handwriting on the server, and the existing `enrich` step adds romanization and meaning.

The main rule for the capture flow: during a lesson the app saves at once and shows
results later. The app never opens a question or a choice while the user writes.

## Target device

| Item | Value |
|---|---|
| Model | Boox Note Air2 Plus (`NoteAir2P`) |
| Android | 11 (API 30) |
| Screen | 1404 × 1872 pixels, 240 dpi, e-ink, portrait |
| Pen | Wacom digitizer, driven by the Onyx pen library |

## Project structure

The repository gets three Gradle modules.

| Module | Content | Minimum Android |
|---|---|---|
| `:core` (library) | `VocabStore`, `Contexts`, `ContextSetting`, `Supabase`, `SyncWorker`, `Updater`, and the dialogs that both apps use (connection, labels) | 11 (API 30) |
| `:app` | The phone recorder. Same behaviour as today. Keeps `WavRecorder` and its own screen. | 13 (API 33) |
| `:boox` | The tablet app: pen canvas, tablet screen, e-ink theme. | 11 (API 30) |

Changes that the move to `:core` requires:

- `SyncWorker` takes the `source` name and the list of attachments from the app, instead
  of the fixed value `field_recorder` and the fixed WAV upload.
- `Updater` takes the release file names from the app. It also replaces the
  `PackageInfoFlags` call, which exists only from Android 13.
- Each app is built by name: `./gradlew :app:assembleRelease` or
  `./gradlew :boox:assembleRelease`.
- The GitHub workflow uses path filters. A change in `app/` builds the recorder, a change
  in `boox/` builds the tablet app, and a change in `core/` builds both apps.
- The tablet app is published in the same "latest" release as `thai-vocab-boox.apk` with
  its own `version-boox.json`, so the one-tap update works on the tablet too.

## Screen

From top to bottom, as in the mockup:

1. **Status line.** Shows the sync state. A tap opens the connection window.
2. **List of entries, newest first.** Each row shows the handwriting image on the left.
   On the right, the row shows the Thai text, then the romanization and the English
   meaning from `enrich`. A row from phonetic writing shows the mark GUESSED and the text
   that the user wrote. If the user wrote a translation, the row shows that translation
   on a separate line.
3. **Context labels.** The same labels as on the phone, including the user's own labels.
4. **Writing box with two zones.** The upper zone is for the Thai or phonetic word. The
   lower zone is optional and holds the user's English translation. A stroke belongs to
   the zone where the stroke starts.
5. **Buttons.** `Clear`, a keyboard button to type a word, `Next: Phonetic` and
   `Next: ไทย`. The two Next buttons are active when the upper zone contains ink.

The theme uses black and white only and switches off animations and ripple effects,
because the screen is e-ink.

Every created thing can be edited and deleted by a tap on the thing itself (rule in
`CLAUDE.md`). A tap on a row opens the edit window with: Thai text, notes, context,
**Read again as the other type**, **Remove handwriting image**, and **Delete**.

## Pen input

The writing box is an Android `SurfaceView` inside the Compose screen. The Onyx pen
library (`onyxsdk-pen`, class `TouchHelper`) draws the ink on that surface directly, which
removes the delay of normal Android drawing on e-ink. The library also delivers the points
of each stroke to the app. The app keeps the strokes in memory until the user presses a
Next button or `Clear`.

If the Onyx library cannot start (another device, an emulator, a preview), the app draws
the strokes with normal Android touch events. This path also makes screenshots and tests
possible on a computer.

When the user presses a Next button, the app draws the strokes of each zone into a PNG
image: black ink on white, cropped to the ink with a margin, at most 1000 pixels wide.

## Data flow

1. **Save.** The app writes the PNG files and a local entry (status `pending`), clears
   the writing box, and schedules the sync job. This step does not need a network
   connection.
2. **Upload.** The sync job uploads the images to the storage bucket `vocab-handwriting`
   and upserts the row with `source = 'boox_handwriting'`.
3. **Read.** The sync job calls the Edge Function `process` with `step: "read"`. The
   function downloads the images, sends them to Claude, saves the result in the row, and
   returns the row. The list then shows the Thai text.
4. **Enrich.** The sync job calls `process` with `step: "enrich"`, as the phone does after
   `transcribe`. The list then shows the romanization and the meaning.

Without a network connection, the entry stays on the tablet with the status `pending`,
and the row shows only the handwriting image. The sync job runs again when a connection is
available.

## Server: new step `read` in `process`

Input stays `{"item_id": "...", "step": "read"}`. The function takes the script type from
the row (`written_script`), so a retry and "Read again as the other type" use the same call.

| Script type | Instruction to Claude | Saved in the row |
|---|---|---|
| `thai` | Read the Thai script in the image. Return the text and up to four other readings. | `thai`, `thai_alternatives`, `written_text` = the text as read, `transcription_status = 'done'` |
| `phonetic` | Read the Latin letters. Return them as written, and the most likely Thai word or phrase. | `written_text` = the Latin letters, `thai` = the Thai guess, `thai_alternatives`, `transcription_status = 'guessed'` |

For both types:

- If the row has an image of the lower zone, the function sends that image too. Claude
  returns the English text as written, which the function saves in `written_english`.
  The instruction tells Claude to use that English text as a hint for the Thai reading.
- The step uses the same model setting (`CLAUDE_MODEL`), the same JSON schema output and
  the same error handling as `enrich`. A failure sets `transcription_status = 'failed'`
  and `transcription_error`.
- The step skips rows where `transcription_status = 'manual'`.

Change to the existing `enrich` step: for rows with `source = 'boox_handwriting'`, the
prompt says that the text comes from handwriting, and passes `written_english` as the
learner's own translation. `enrich` still writes its own meaning into `english`. Both
meanings are kept.

The Anthropic key is already stored in the Supabase secrets (`ANTHROPIC_API_KEY`).

## Database migration

One new file in `supabase/migrations/`:

| Addition | Purpose |
|---|---|
| `vocab_items.image_path` | Handwriting image of the upper zone: `<user id>/<item id>.png` |
| `vocab_items.english_image_path` | Handwriting image of the lower zone: `<user id>/<item id>-en.png` |
| `vocab_items.written_script` | `thai` or `phonetic`, set by the Next button |
| `vocab_items.written_text` | The upper zone as written (Thai script or Latin letters) |
| `vocab_items.written_english` | The lower zone as written |
| Bucket `vocab-handwriting` | Private, with the same four access rules as `vocab-audio` |
| View `vocab_active` | Recreated, because the view lists all columns |

The new status value `guessed` for `transcription_status` fits the existing text column.
The phone recorder shows rows from the tablet in its list through the existing download.

## Testing

- **Unit tests** for `:core` after the move (store, row conversion), run on the computer.
- **Server step:** run `read` against photographs of real handwriting by the user, for
  both script types, with and without the English zone. Compare the result with the
  expected text.
- **Screens:** Paparazzi screenshots of the tablet screen, as for the phone.
- **Device:** install the tablet app over USB and write real entries, online and offline.
- **Recorder regression:** build and install the phone app after the move to `:core`,
  and record one word.

## Open points for the build

- Confirm the current version of `onyxsdk-pen` and its Maven repository. Only unofficial
  documentation was found, and two sources name different latest versions.
- Automatic save after a pause is left for a later version.
