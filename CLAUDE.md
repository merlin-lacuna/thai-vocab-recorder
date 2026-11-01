# Thai Vocab Recorder – notes for AI agents

- Android app (Kotlin, Jetpack Compose) in `app/`, database and server functions for
  Supabase in `supabase/`. Overview and set-up: `README.md`.
- **Batch import of existing recordings or transcriptions into the database:**
  follow `docs/batch-import.md`.
- Database changes: add a new file in `supabase/migrations/`; never edit an applied one.
- The user prefers plain international English (no idioms) in all messages.
