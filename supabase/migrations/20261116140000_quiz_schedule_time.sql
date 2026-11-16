-- quiz_schedule: send time per weekday (local time in Asia/Bangkok).
-- The mail job on the user's server asks for today's time shortly after midnight
-- (action=plan of the Edge Function daily-quiz) and sets a one-time timer for it.
alter table public.quiz_schedule add column if not exists send_time time not null default '07:00';
