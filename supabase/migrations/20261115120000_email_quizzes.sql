-- Quiz emails: one row per email. The email shows an English sentence with the target
-- word highlighted and six Thai sentences; one of them is the translation.
-- The Edge Function "daily-quiz" reads the row when a choice is clicked and writes one
-- quiz_events row per click.

create table if not exists public.email_quizzes (
    id            uuid primary key default gen_random_uuid(),
    user_id       uuid not null default auth.uid() references auth.users (id) on delete cascade,
    item_id       uuid not null references public.vocab_items (id) on delete cascade,
    sentence_en   text not null,          -- "Yesterday, I went to see my godson."
    highlight     text not null,          -- the part of sentence_en to highlight: "godson"
    -- [{ "thai": ..., "romanization": ..., "english": ..., "correct": true|false }], in display order
    options       jsonb not null,
    created_at    timestamptz not null default now(),
    sent_at       timestamptz
);

create index if not exists email_quizzes_user_idx on public.email_quizzes (user_id, created_at);

alter table public.email_quizzes enable row level security;

drop policy if exists "own email quizzes" on public.email_quizzes;
create policy "own email quizzes" on public.email_quizzes
    for all to authenticated
    using (user_id = auth.uid())
    with check (user_id = auth.uid());
