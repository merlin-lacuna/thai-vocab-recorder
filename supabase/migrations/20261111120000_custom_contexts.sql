-- User-defined capture contexts (labels), in addition to the built-in ones of the app:
-- field, wondering, lingping_club, and lesson + teacher (Boom, Ae).
-- vocab_items.context stores the label's key; the label text lives here, so a rename
-- changes every item at once.
create table if not exists public.capture_contexts (
    user_id     uuid not null default auth.uid() references auth.users (id) on delete cascade,
    key         text not null check (key ~ '^[a-z0-9_]{1,40}$'),
    label       text not null check (length(label) between 1 and 40),
    sort_order  integer not null default 100,
    hidden      boolean not null default false,  -- removed from the selection; old items keep it
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now(),
    primary key (user_id, key)
);

alter table public.capture_contexts enable row level security;

drop policy if exists "own contexts" on public.capture_contexts;
create policy "own contexts" on public.capture_contexts
    for all to authenticated
    using (user_id = auth.uid())
    with check (user_id = auth.uid());

-- Items may now use any context key (built-in or from capture_contexts).
alter table public.vocab_items drop constraint if exists vocab_items_context_check;
alter table public.vocab_items add constraint vocab_items_context_check
    check (context ~ '^[a-z0-9_]{1,40}$');
