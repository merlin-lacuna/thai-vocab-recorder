-- Why the AI enrichment step (Gemini) failed, if it did.
alter table public.vocab_items add column if not exists enrichment_error text;
