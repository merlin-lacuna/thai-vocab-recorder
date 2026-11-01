-- Thai vocabulary database (Supabase / PostgreSQL) - first version of the schema.
--
-- Supabase applies the files in supabase/migrations/ automatically, in name order,
-- through the GitHub integration (when the branch is merged into the production branch).
-- Manual alternative: paste this file into the Supabase SQL Editor and click Run.
--
-- For later changes, add a NEW file in supabase/migrations/ (for example
-- 20261215090000_add_drill_columns.sql). Do not edit a file that was already applied.
--
-- Design:
--   * vocab_items is the shared table. Every input source (the field recorder app,
--     later a lesson-session recorder, imports, ...) writes rows here and sets `source`.
--   * The drill app writes one row per quiz question into quiz_events. The view
--     vocab_stats gives "times asked" and "times correct" per item; the events
--     themselves are kept for weekly time-series analysis.
--   * Audio clips live in the private Storage bucket "vocab-audio":
--       recordings:   "<user id>/<item id>.wav"      -> vocab_items.audio_path
--       generated TTS: "<user id>/tts/<item id>.mp3" -> vocab_items.tts_audio_path
--   * Row Level Security: each signed-in user sees only their own rows.

create table if not exists public.vocab_items (
    id                    uuid primary key default gen_random_uuid(),
    user_id               uuid not null default auth.uid() references auth.users (id) on delete cascade,
    captured_at           timestamptz not null default now(),

    -- Where the word was heard
    context               text not null default 'field'
                          check (context in ('field', 'lingping_club', 'lesson', 'other')),
    teacher               text,                          -- e.g. 'Boom', 'Ae' (only for lessons)
    source                text not null default 'manual', -- input app: 'field_recorder', 'lesson_recorder', 'pickup_thai', ...

    -- What was captured
    thai                  text,                          -- Thai script (speech recognition or typed)
    thai_alternatives     text[] not null default '{}',  -- other recognition candidates
    notes                 text,
    audio_path            text,                          -- original recording, always kept
    transcription_status  text,                          -- pending | done | failed | manual

    -- Filled in later (by hand, by an AI enrichment job, or in an admin UI)
    romanization          text,
    english               text,
    entry_type            text check (entry_type in ('word', 'phrase')),
    part_of_speech        text check (part_of_speech in (
                              'noun', 'verb', 'adjective', 'adverb', 'preposition', 'article',
                              'pronoun', 'classifier', 'particle', 'conjunction', 'number', 'other')),
    tts_audio_path        text,                          -- clean computer voice for the drill app
    tags                  text[] not null default '{}',
    enrichment_status     text not null default 'new'
                          check (enrichment_status in ('new', 'enriched', 'reviewed')),

    -- Clean-up (deduping, junk, mispronunciations)
    quality               text not null default 'ok'
                          check (quality in ('ok', 'junk', 'mispronounced', 'duplicate')),
    duplicate_of          uuid references public.vocab_items (id) on delete set null,

    created_at            timestamptz not null default now(),
    updated_at            timestamptz not null default now(),
    deleted_at            timestamptz                    -- soft delete; drill apps ignore these rows
);

create index if not exists vocab_items_user_captured_idx on public.vocab_items (user_id, captured_at desc);
create index if not exists vocab_items_enrichment_idx on public.vocab_items (enrichment_status) where deleted_at is null;

-- One row each time the drill app asks a question.
create table if not exists public.quiz_events (
    id              uuid primary key default gen_random_uuid(),
    user_id         uuid not null default auth.uid() references auth.users (id) on delete cascade,
    item_id         uuid not null references public.vocab_items (id) on delete cascade,
    asked_at        timestamptz not null default now(),
    answered_at     timestamptz,                         -- null = not answered
    correct         boolean,
    quiz_type       text,                                -- e.g. 'thai_to_english', 'listen_and_say'
    answer          text                                 -- what the user answered, if useful
);

create index if not exists quiz_events_user_time_idx on public.quiz_events (user_id, asked_at);
create index if not exists quiz_events_item_idx on public.quiz_events (item_id);

-- Row Level Security --------------------------------------------------------

alter table public.vocab_items enable row level security;
alter table public.quiz_events enable row level security;

drop policy if exists "own vocab" on public.vocab_items;
create policy "own vocab" on public.vocab_items
    for all to authenticated
    using (user_id = auth.uid())
    with check (user_id = auth.uid());

drop policy if exists "own quiz events" on public.quiz_events;
create policy "own quiz events" on public.quiz_events
    for all to authenticated
    using (user_id = auth.uid())
    with check (user_id = auth.uid());

-- Audio storage --------------------------------------------------------------

insert into storage.buckets (id, name, public)
values ('vocab-audio', 'vocab-audio', false)
on conflict (id) do nothing;

drop policy if exists "own audio read" on storage.objects;
create policy "own audio read" on storage.objects
    for select to authenticated
    using (bucket_id = 'vocab-audio' and (storage.foldername(name))[1] = auth.uid()::text);

drop policy if exists "own audio insert" on storage.objects;
create policy "own audio insert" on storage.objects
    for insert to authenticated
    with check (bucket_id = 'vocab-audio' and (storage.foldername(name))[1] = auth.uid()::text);

drop policy if exists "own audio update" on storage.objects;
create policy "own audio update" on storage.objects
    for update to authenticated
    using (bucket_id = 'vocab-audio' and (storage.foldername(name))[1] = auth.uid()::text);

drop policy if exists "own audio delete" on storage.objects;
create policy "own audio delete" on storage.objects
    for delete to authenticated
    using (bucket_id = 'vocab-audio' and (storage.foldername(name))[1] = auth.uid()::text);

-- Views for the drill app ----------------------------------------------------

-- Live, usable items only.
create or replace view public.vocab_active with (security_invoker = true) as
    select * from public.vocab_items
    where deleted_at is null and quality = 'ok';

-- Times asked and times correct per item.
create or replace view public.vocab_stats with (security_invoker = true) as
    select v.id as item_id,
           count(e.id)                                 as times_asked,
           count(e.id) filter (where e.correct)        as times_correct,
           max(e.asked_at)                             as last_asked_at
    from public.vocab_items v
    left join public.quiz_events e on e.item_id = v.id
    where v.deleted_at is null
    group by v.id;

-- Results per week, for time-series analysis.
create or replace view public.quiz_weekly with (security_invoker = true) as
    select date_trunc('week', asked_at)                as week,
           count(*)                                    as asked,
           count(*) filter (where correct)             as correct,
           round(100.0 * count(*) filter (where correct) / nullif(count(*), 0), 1) as percent_correct
    from public.quiz_events
    group by 1
    order by 1;
