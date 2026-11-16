-- Rules for the quiz emails, one row per weekday (Asia/Bangkok time).
-- The Edge Function "daily-quiz" reads the row for today when the mail job on the server
-- asks for a quiz:
--   send = false   -> no email that day
--   teacher = null -> quiz on any word
--   teacher = 'Boom' -> quiz only on words with vocab_items.teacher = 'Boom'
-- Edit the rows in the Supabase table editor; the next email uses the new values.

create table if not exists public.quiz_schedule (
    user_id    uuid not null default auth.uid() references auth.users (id) on delete cascade,
    weekday    smallint not null check (weekday between 1 and 7),   -- 1 = Monday ... 7 = Sunday
    day_name   text not null,                                       -- for reading only
    send       boolean not null default true,
    teacher    text,
    primary key (user_id, weekday)
);

alter table public.quiz_schedule enable row level security;

drop policy if exists "own quiz schedule" on public.quiz_schedule;
create policy "own quiz schedule" on public.quiz_schedule
    for all to authenticated
    using (user_id = auth.uid())
    with check (user_id = auth.uid());

-- Starting rules: Kruu Boom on Wednesday and Friday, Kruu Ae on Thursday, any word on the other days.
insert into public.quiz_schedule (user_id, weekday, day_name, send, teacher)
select u.id, d.weekday, d.day_name, true, d.teacher
from (select user_id as id from public.vocab_items group by user_id order by count(*) desc limit 1) u  -- the vocabulary owner
cross join (values
    (1, 'Monday', null), (2, 'Tuesday', null), (3, 'Wednesday', 'Boom'), (4, 'Thursday', 'Ae'),
    (5, 'Friday', 'Boom'), (6, 'Saturday', null), (7, 'Sunday', null)
) as d(weekday, day_name, teacher)
on conflict do nothing;
