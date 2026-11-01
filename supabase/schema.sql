-- Thai vocabulary database (Supabase / PostgreSQL)
--
-- Run this whole file once in the Supabase dashboard: SQL Editor -> New query -> Run.
-- It is safe to run again: it only creates what is missing.
--
-- Design:
--   * vocab_items is the shared table. Every input source (the field recorder app,
--     later imports, a web form, ...) writes rows here and sets `source`.
--   * The drill app reads vocab_items and keeps its own learning progress in
--     review_state (one row per item), so the input apps never touch drill data.
--   * Audio clips live in the private Storage bucket "vocab-audio",
--     at "<user id>/<item id>.wav". vocab_items.audio_path holds that path.
--   * Row Level Security: each signed-in user sees only their own rows.

create table if not exists public.vocab_items (
    id                    uuid primary key default gen_random_uuid(),
    user_id               uuid not null default auth.uid() references auth.users (id) on delete cascade,

    -- What was captured
    thai                  text,                          -- Thai script (speech recognition or typed)
    thai_alternatives     text[] not null default '{}',  -- other recognition candidates
    notes                 text,                          -- free text from the user
    audio_path            text,                          -- path in the "vocab-audio" bucket

    -- Filled in later (by hand, by an AI enrichment job, or by the drill app)
    meaning               text,                          -- English meaning
    romanization          text,                          -- e.g. Paiboon or RTGS
    part_of_speech        text,
    example               text,
    tags                  text[] not null default '{}',

    -- Where it came from and how far it has been processed
    source                text not null default 'manual', -- 'field_recorder', 'import', ...
    transcription_status  text,                          -- pending | done | failed | manual
    enrichment_status     text not null default 'new',   -- new | enriched | reviewed

    captured_at           timestamptz not null default now(),
    created_at            timestamptz not null default now(),
    updated_at            timestamptz not null default now(),
    deleted_at            timestamptz                    -- soft delete; drill apps should ignore these rows
);

create index if not exists vocab_items_user_captured_idx on public.vocab_items (user_id, captured_at desc);
create index if not exists vocab_items_enrichment_idx on public.vocab_items (enrichment_status) where deleted_at is null;

-- Learning progress for the future drill app (spaced repetition).
create table if not exists public.review_state (
    item_id         uuid primary key references public.vocab_items (id) on delete cascade,
    user_id         uuid not null default auth.uid() references auth.users (id) on delete cascade,
    due_at          timestamptz not null default now(),
    interval_days   real not null default 0,
    ease            real not null default 2.5,
    repetitions     integer not null default 0,
    lapses          integer not null default 0,
    last_reviewed_at timestamptz
);

create index if not exists review_state_due_idx on public.review_state (user_id, due_at);

-- Row Level Security --------------------------------------------------------

alter table public.vocab_items enable row level security;
alter table public.review_state enable row level security;

drop policy if exists "own vocab" on public.vocab_items;
create policy "own vocab" on public.vocab_items
    for all to authenticated
    using (user_id = auth.uid())
    with check (user_id = auth.uid());

drop policy if exists "own reviews" on public.review_state;
create policy "own reviews" on public.review_state
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

-- Convenience view for drill apps: live items only.
create or replace view public.vocab_active with (security_invoker = true) as
    select * from public.vocab_items where deleted_at is null;
