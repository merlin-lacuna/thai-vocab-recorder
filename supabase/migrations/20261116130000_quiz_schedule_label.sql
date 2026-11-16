-- quiz_schedule: one filter field "label" instead of "teacher".
--   label = null          -> quiz on any word
--   label = 'wondering'   -> words with this label (vocab_items.context)
--   label = 'Boom'        -> words from lessons with this teacher (vocab_items.teacher)
alter table public.quiz_schedule rename column teacher to label;
