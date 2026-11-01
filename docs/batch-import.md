# Batch import into the Thai vocabulary database

Instructions for an AI agent (or a developer) who adds **existing** recordings and
transcriptions to the Supabase database in one batch, without the phone app.

Read the whole document before you start. Do a dry run first (step 5).

---

## 1. Goal and rules

Add vocabulary items from existing material (for example lesson recordings, audio clips,
transcripts, word lists) to the table `public.vocab_items`, so that:

- the phone app shows them (it downloads the newest 500 items by `captured_at`),
- the future drill app can use them,
- each item has Thai text, romanization and English – **from the source data where it
  exists**, and from the AI step (`process` function) only for the fields that are missing.

Rules:

1. **Never change or delete existing rows**, except where this document says so
   (duplicates in step 4.4). Do not touch rows with `enrichment_status = 'reviewed'`.
2. **Do not write to `quiz_events`.** That table belongs to the drill app.
3. **Re-runs must be safe (idempotent).** Running the import twice must not create
   duplicates. Use deterministic IDs (step 4.1) and upserts.
4. **Never print, log or commit keys or passwords.** Read them from environment variables.
5. **Report before you write.** Show the dry-run summary to the user and wait for approval
   before the real import, unless the user said otherwise.

---

## 2. The project

| Item | Value |
|---|---|
| Supabase project ref | `vazojhdllcilzbdtavww` (region ap-northeast-2) |
| Project URL | `https://vazojhdllcilzbdtavww.supabase.co` |
| Publishable key (public by design) | `sb_publishable_zQgiMRTpOkwrAMgFVb-EgA_wf8EFdSt` |
| App user (owner of all vocabulary) | `merltron.cnx@gmail.com`, user id `c8e00ce7-52e5-47ff-aee2-9bffc418e428` |
| Audio bucket (private) | `vocab-audio` |
| Schema | `supabase/migrations/*.sql` in this repository (apply new changes as new migration files) |
| AI step (Edge Function) | `supabase/functions/process/index.ts`, deployed as `process` |

Ignore the user `cryptomerlino@gmail.com`; it is an old test account with no data.

### Access

There are two ways to write. Use **A** for data, and **B** only for checks and schema changes.

**A. As the app user, through the REST API (recommended for the import).**
Row Level Security applies, exactly as for the phone app, so mistakes cannot affect other
users' data or other tables.

```bash
# The user gives the password as an environment variable, e.g. VOCAB_PASSWORD.
curl -s "$URL/auth/v1/token?grant_type=password" \
  -H "apikey: $PUBLISHABLE_KEY" -H "Content-Type: application/json" \
  -d "{\"email\":\"merltron.cnx@gmail.com\",\"password\":\"$VOCAB_PASSWORD\"}"
# -> access_token (valid about 1 hour; refresh with grant_type=refresh_token)
```

Then send `apikey: $PUBLISHABLE_KEY` and `Authorization: Bearer $ACCESS_TOKEN` on every request.

**B. Supabase Management API (SQL as the database owner).**
`POST https://api.supabase.com/v1/projects/vazojhdllcilzbdtavww/database/query`
with body `{"query": "..."}`. It needs a Supabase personal access token. In the cloud
environment of the original session, this token is stored as a *network secret* for
`api.supabase.com`, so requests work without an Authorization header. This route bypasses
Row Level Security: `auth.uid()` is null, so you must set `user_id` explicitly. The API
sometimes answers with a temporary HTTP 502 page; retry after 10 seconds.

Network hosts needed: `*.supabase.co` and `api.supabase.com`.

---

## 3. Data model (table `public.vocab_items`)

| Column | Type | Rules for the import |
|---|---|---|
| `id` | uuid, PK | Deterministic UUID, see 4.1 |
| `user_id` | uuid | Set automatically with access A; with access B use `c8e00ce7-52e5-47ff-aee2-9bffc418e428` |
| `captured_at` | timestamptz | A date from the source if it has one (for example a lesson date or a date column). Otherwise one fixed timestamp for the whole batch; do not spend effort on it. See the note below the table. |
| `context` | text | `field` (daily life), `wondering` (looked up out of curiosity), `lingping_club` (weekly practice group), `lesson`, `other` |
| `teacher` | text | Only for `context = 'lesson'`: `Boom` or `Ae` (exact spelling, the app maps these) |
| `source` | text | Name of the input, for example `lesson_recorder`, `pickup_thai`, `import_<name>`. Never `field_recorder` (that is the phone app). |
| `thai` | text | Thai script. Null if only audio exists (the AI step transcribes it). |
| `thai_alternatives` | text[] | Other spellings or recognition candidates; default `{}` |
| `notes` | text | Free text, for example "Lesson 2026-10-28, 12:34–12:37" or an example sentence |
| `audio_path` | text | `<user_id>/<id>.wav` in bucket `vocab-audio`, or null |
| `transcription_status` | text | `done` (machine transcript), `manual` (typed or verified by a human), `pending` (audio only, not transcribed yet) |
| `romanization`, `english`, `entry_type`, `part_of_speech` | text | From the source data when it has them; otherwise null (see 4.0) |
| `enrichment_status` | text | **Controls the AI step**, see 4.0: `new` = the AI step may process the row (also the phone app processes up to 20 such rows at each sync); `enriched` = filled by a machine, AI leaves it alone; `reviewed` = checked by a human, AI never changes it |
| `quality`, `duplicate_of` | text, uuid | `ok` by default; see 4.4 for duplicates; `junk` / `mispronounced` for bad items |
| `tags` | text[] | Optional, for example `{lesson-2026-10-28}` – useful to find or undo one batch |
| `deleted_at` | timestamptz | Soft delete. Leave null. |
| `created_at`, `updated_at` | timestamptz | Set automatically. A trigger updates `updated_at` on every update. |

Allowed values are enforced by CHECK constraints:
`entry_type` in (`word`, `phrase`);
`part_of_speech` in (`noun`, `verb`, `adjective`, `adverb`, `preposition`, `article`,
`pronoun`, `classifier`, `particle`, `conjunction`, `number`, `other`).

Note on `captured_at`: the phone app shows the newest 500 items by `captured_at`. A batch
with today's date appears at the top of the phone list. For a large batch (more than about
200 items without a real date), ask the user whether to use an early fixed date instead
(for example `2026-01-01T00:00:00Z`), so the imported words do not push the words recorded
on the phone out of that list.

Always add a tag for the batch (for example `import-2026-11-05`), so that the user can
find, check or undo it with one query.

---

## 4. Procedure

### 4.0 Decide per item: source data or AI step

Use the data you already have. Call the AI step only for what is missing. Decide once per
batch (or per item when the source is mixed):

| The source has … | Write in the upsert | `enrichment_status` | AI step (4.7) |
|---|---|---|---|
| Thai, romanization, English (human-made or checked) | all of them | `reviewed` | no |
| Thai, romanization, English (machine-made, good quality) | all of them | `enriched` | no |
| Thai + romanization, no English | Thai, romanization | `enriched` (English stays empty) **or** `new` if English is wanted | only if `new`, see note |
| Thai only | Thai | `new` | yes (enrichment only) |
| Audio only | `audio_path`, `transcription_status = 'pending'` | `new` | yes (transcription + enrichment) |

Notes:

- **Any row with `enrichment_status = 'new'` and Thai text gets processed by the AI**,
  either by your 4.7 run or by the phone app (up to 20 rows per sync). The AI step
  overwrites `thai` (spelling fix), `romanization`, `english`, `entry_type` and
  `part_of_speech`. So set `enriched` or `reviewed` for every row whose values must stay.
- **Romanization system:** the app and the AI use strict Paiboon+ (tone marks, ต = dt,
  ป = bp, vowels ɔ ɛ ə ʉ; full rules in `ROMANIZATION_RULES` in
  `supabase/functions/process/index.ts`). If the source uses another system, ask the user
  whether to keep it as it is, or to let the AI replace it (then set `new`).
- Set `transcription_status = 'manual'` for Thai text typed or verified by a human,
  `done` for machine transcripts.
- **Partial gaps in large numbers** (for example 2,000 rows without English): calling
  `process` once per row is slow. Ask the user first; a batch version of the AI step
  (many rows per Gemini request) can be added to the Edge Function for this case.

### 4.1 Prepare the items (offline, no writes)

1. Read the source material and build a list of items: Thai text (if known), audio segment
   (file + start/end time, if any), date (only if the source has one), context, teacher, notes.
2. Normalize the Thai text: trim spaces, remove zero-width characters (U+200B), apply
   Unicode NFC. Do not "correct" spelling yourself; the AI step does that and keeps the
   original in `thai_alternatives`.
3. Split phrases and words sensibly: one item per word or short phrase that the learner
   wants to drill. Skip filler words and names unless the user wants them.
4. **Deterministic ID:** `id = uuid5(NAMESPACE, "<source>|<source file>|<start ms>|<thai>")`
   with a fixed namespace UUID (choose one and write it into your script). The same input
   then always gives the same ID, so re-runs update instead of duplicating.

### 4.2 Audio clips

The phone app downloads `audio_path` to a local file and plays it with Android MediaPlayer.
Use this format for every clip:

- **WAV, 16 kHz, mono, 16-bit PCM** (the format of the app's own recordings; ElevenLabs
  accepts it too), for example:
  `ffmpeg -i in.m4a -ss 754.2 -to 757.9 -ac 1 -ar 16000 -sample_fmt s16 out.wav`
- One clip per item, 0.3–1.0 s of margin before and after the word (Thai final stops
  such as -t, -p, -k are almost silent and get lost in tight cuts). At most 60 seconds.
- Upload to `vocab-audio` at `<user_id>/<id>.wav`:
  `POST $URL/storage/v1/object/vocab-audio/<user_id>/<id>.wav` with
  `Content-Type: audio/wav`, `x-upsert: true` and the access-A headers.
  The folder must be the user id; the storage security rules reject other paths.
- Full-length originals (for example a 1-hour lesson) may go to
  `<user_id>/lessons/<file name>`. Do not set them as `audio_path` of an item.

### 4.3 Check for duplicates (read only)

Load the existing items once:
`GET $URL/rest/v1/vocab_items?select=id,thai,source,deleted_at&deleted_at=is.null&limit=10000`

Compare the normalized Thai text of each new item with the existing ones and with the other
new items. Report: new items, exact duplicates of existing rows, duplicates inside the batch.

### 4.4 Duplicates – default rule

- Duplicate inside the batch: keep the first (best audio), drop the others.
- Duplicate of an existing row: **insert it anyway** with `quality = 'duplicate'` and
  `duplicate_of = <existing id>`, so the extra audio sample is kept but the drill app can
  ignore it. If the user prefers, skip it instead.

### 4.5 Dry run (required)

Without writing anything, show the user:

- number of items, split by context/teacher and by "with text" / "audio only",
- the first 20 items (Thai, notes, captured_at, audio length),
- duplicates found and what you will do with them,
- how many rows use source values only, and how many need the AI step (and which part),
- the estimated AI cost: ElevenLabs is billed per audio minute (only items without
  Thai text are transcribed); Gemini costs much less than 1 US cent per 100 items.

Wait for approval.

### 4.6 Write

1. Upload the audio clips first (4.2). Retry each upload up to 3 times.
2. Upsert the rows in batches of at most 500:
   `POST $URL/rest/v1/vocab_items?on_conflict=id` with the header
   `Prefer: resolution=merge-duplicates,return=minimal` and a JSON array of rows.
   Do not send `romanization`, `english`, `entry_type`, `part_of_speech` or
   `enrichment_status` for re-runs of existing IDs, unless you mean to overwrite them.
3. Log each batch: number of rows, HTTP status. Stop at the first 4xx error and report it.

### 4.7 AI step (transcription + enrichment) – only for rows that need it

Skip this step for rows with `enrichment_status` `enriched` or `reviewed` (see 4.0).
For the other rows, call the deployed Edge Function per item:

```
POST $URL/functions/v1/process
Authorization: Bearer $ACCESS_TOKEN
apikey: $PUBLISHABLE_KEY
Content-Type: application/json

{"item_id": "<id>"}
```

What it does (see `supabase/functions/process/index.ts`):

- `thai` is null and `audio_path` is set → ElevenLabs speech-to-text (Thai), saves `thai`.
- Then, unless `enrichment_status = 'reviewed'` → Gemini returns standard spelling, strict
  Paiboon+ romanization (ต = dt, ป = bp), English, `entry_type`, `part_of_speech`.
  A corrected spelling replaces `thai`; the original goes into `thai_alternatives`.
- Returns the updated row. Takes about 3–6 seconds per item.

Batch rules:

- Run **2–3 requests in parallel at most**. Gemini sometimes answers "high demand" (503);
  the function then tries other models, and if all are busy it saves **no** error and leaves
  `enrichment_status = 'new'`.
- After the run, process the remaining rows again:
  `select id from vocab_items where enrichment_status = 'new' and thai is not null and enrichment_error is null and deleted_at is null`.
  Repeat with a pause (for example 1 minute) until nothing changes.
- Rows with `enrichment_error` or `transcription_error` have a permanent problem; list them
  in the report, do not retry them in a loop.
- The phone app also processes up to 20 such rows at each sync, so a few leftovers are fine.

### 4.8 Check and report

Run these checks (access B, or REST with filters) and report the numbers:

```sql
-- Items of this batch by status
select enrichment_status, transcription_status, quality, count(*)
from vocab_items where 'import-2026-11-05' = any(tags) group by 1, 2, 3;

-- Items with errors
select id, thai, transcription_error, enrichment_error
from vocab_items where 'import-2026-11-05' = any(tags)
  and (transcription_error is not null or enrichment_error is not null);

-- Rows that point to a missing audio file
select v.id, v.audio_path from vocab_items v
left join storage.objects o on o.bucket_id = 'vocab-audio' and o.name = v.audio_path
where 'import-2026-11-05' = any(v.tags) and v.audio_path is not null and o.id is null;

-- A random sample for the user to check
select thai, romanization, english, notes from vocab_items
where 'import-2026-11-05' = any(tags) order by random() limit 15;
```

### 4.9 Undo (only if the user asks)

Soft delete keeps the data and also removes the items from the phone at its next sync:

```sql
update vocab_items set deleted_at = now() where 'import-2026-11-05' = any(tags);
```

---

## 5. Checklist

- [ ] Sign-in works (access A); password read from an environment variable
- [ ] Items prepared; deterministic IDs; batch tag chosen
- [ ] Per item decided: values from the source, `enrichment_status`, AI step yes/no (4.0)
- [ ] Audio cut to 16 kHz mono WAV with margins
- [ ] Duplicate check done
- [ ] Dry-run summary shown; user approved
- [ ] Audio uploaded, then rows upserted (≤ 500 per request)
- [ ] `process` called for every new item (≤ 3 in parallel), leftovers retried
- [ ] Checks in 4.8 run; report with numbers, errors and a sample sent to the user
