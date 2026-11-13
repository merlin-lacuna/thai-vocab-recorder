# Thai Vocab Recorder – notes for AI agents

- Two Android apps (Kotlin, Jetpack Compose): the phone recorder in `app/` and the Boox
  tablet app in `boox/`. Shared code (store, sync, updater, shared windows) is in `core/`;
  a change there affects both apps, so build both. Database and server functions for
  Supabase are in `supabase/`. Overview and set-up: `README.md`.
- Gradle needs JDK 17 or newer. On the user's Windows computer:
  `export JAVA_HOME="$HOME/.jdks/jbr-21.0.11"`.
- The Boox freezes an app after an install over USB. Run
  `adb shell pm enable app.thaivocab.boox` before `adb shell am start`.
- **Batch import of existing recordings or transcriptions into the database:**
  follow `docs/batch-import.md`.
- Database changes: add a new file in `supabase/migrations/`; never edit an applied one.
- The user prefers plain international English (no idioms) in all messages.
- Describe devices and software in mechanical terms: no "the phone knows", "the app
  wants", "the model thinks". Say what the code does, e.g. "the app sends the column only
  when the local copy has a value".
- **Every thing the user can create in the app must also be editable and deletable, by
  tapping the thing itself** (a word, a label, a clip, a setting). A separate management
  window may exist in addition, never instead. Check this for every new feature before
  committing it.
