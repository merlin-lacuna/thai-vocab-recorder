-- Handwriting from the Boox tablet app (source = 'boox_handwriting').
-- The writing box has two zones. Each zone is stored as one PNG file in the private
-- Storage bucket "vocab-handwriting":
--   upper zone (Thai script or phonetic Latin letters): "<user id>/<item id>.png"
--   lower zone (the learner's English translation):     "<user id>/<item id>-en.png"
alter table public.vocab_items add column if not exists image_path text;
alter table public.vocab_items add column if not exists english_image_path text;

-- Which "Next" button the user pressed. The "read" step of the Edge Function uses it.
alter table public.vocab_items add column if not exists written_script text
    check (written_script in ('thai', 'phonetic'));

-- The two zones as Claude read them. `thai` holds the Thai word; for phonetic writing,
-- `thai` is a guess and transcription_status is 'guessed'.
alter table public.vocab_items add column if not exists written_text text;
alter table public.vocab_items add column if not exists written_english text;

insert into storage.buckets (id, name, public)
values ('vocab-handwriting', 'vocab-handwriting', false)
on conflict (id) do nothing;

drop policy if exists "own handwriting read" on storage.objects;
create policy "own handwriting read" on storage.objects
    for select to authenticated
    using (bucket_id = 'vocab-handwriting' and (storage.foldername(name))[1] = auth.uid()::text);

drop policy if exists "own handwriting insert" on storage.objects;
create policy "own handwriting insert" on storage.objects
    for insert to authenticated
    with check (bucket_id = 'vocab-handwriting' and (storage.foldername(name))[1] = auth.uid()::text);

drop policy if exists "own handwriting update" on storage.objects;
create policy "own handwriting update" on storage.objects
    for update to authenticated
    using (bucket_id = 'vocab-handwriting' and (storage.foldername(name))[1] = auth.uid()::text);

drop policy if exists "own handwriting delete" on storage.objects;
create policy "own handwriting delete" on storage.objects
    for delete to authenticated
    using (bucket_id = 'vocab-handwriting' and (storage.foldername(name))[1] = auth.uid()::text);

-- Views store their own column names: recreate the view that lists all columns.
drop view if exists public.vocab_active;
create view public.vocab_active with (security_invoker = true) as
    select * from public.vocab_items
    where deleted_at is null and quality = 'ok';
