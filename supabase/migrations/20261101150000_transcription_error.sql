-- Why speech recognition failed on the phone (for example "no match", "network error").
alter table public.vocab_items add column if not exists transcription_error text;
