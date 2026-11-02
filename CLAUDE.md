# Thai Vocab Recorder – notes for AI agents

- Android app (Kotlin, Jetpack Compose) in `app/`, database and server functions for
  Supabase in `supabase/`. Overview and set-up: `README.md`.
- **Batch import of existing recordings or transcriptions into the database:**
  follow `docs/batch-import.md`.
- Database changes: add a new file in `supabase/migrations/`; never edit an applied one.
- The user prefers plain international English (no idioms) in all messages.
- Describe devices and software in mechanical terms: no "the phone knows", "the app
  wants", "the model thinks". Say what the code does, e.g. "the app sends the column only
  when the local copy has a value".
