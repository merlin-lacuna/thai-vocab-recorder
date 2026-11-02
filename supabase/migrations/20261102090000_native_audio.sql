-- Native Thai pronunciation of each item (made by a text-to-speech API or a native speaker),
-- for comparison with the learner's own recording in audio_path.
-- Storage path: "<user id>/native/<item id>.<ext>" in the "vocab-audio" bucket.
alter table public.vocab_items rename column tts_audio_path to native_audio_path;

-- Which API and voice made the clip, e.g. "elevenlabs:eleven_v3:<voice id>" or "human:Kru Boom".
alter table public.vocab_items add column if not exists native_audio_source text;

-- Views store their own column names: recreate the view that lists all columns.
drop view if exists public.vocab_active;
create view public.vocab_active with (security_invoker = true) as
    select * from public.vocab_items
    where deleted_at is null and quality = 'ok';
