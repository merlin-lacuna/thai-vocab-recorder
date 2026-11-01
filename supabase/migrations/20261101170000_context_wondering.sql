-- New capture context "wondering": words looked up out of curiosity, not heard in real life.
alter table public.vocab_items drop constraint if exists vocab_items_context_check;
alter table public.vocab_items add constraint vocab_items_context_check
    check (context in ('field', 'wondering', 'lingping_club', 'lesson', 'other'));
