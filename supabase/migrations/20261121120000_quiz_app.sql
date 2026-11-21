-- Quiz app (quiz/): practice quizzes and push messages.
--
-- email_quizzes.source: 'email' = daily email quiz (also extra quizzes sent by email),
--                       'app'   = practice quiz created for the quiz app.
--   The mail job takes only 'email' quizzes.
-- email_quizzes.labels: the label filter that the app used, as a sorted comma list
--   (for example 'Ae,Boom'), or null for any word. The function keeps one prepared app
--   quiz per filter, so the next question is ready at once.
-- email_quizzes.shown_at: when the app showed the quiz. A prepared quiz has shown_at null.

alter table public.email_quizzes
    add column if not exists source text not null default 'email' check (source in ('email', 'app')),
    add column if not exists labels text,
    add column if not exists shown_at timestamptz;

-- UnifiedPush addresses of the quiz app on the user's phones. The Edge Function
-- "daily-quiz" sends a message to each address when a quiz email is sent.
create table if not exists public.push_endpoints (
    user_id    uuid not null default auth.uid() references auth.users (id) on delete cascade,
    endpoint   text not null,
    updated_at timestamptz not null default now(),
    primary key (user_id, endpoint)
);

alter table public.push_endpoints enable row level security;

drop policy if exists "own push endpoints" on public.push_endpoints;
create policy "own push endpoints" on public.push_endpoints
    for all to authenticated
    using (user_id = auth.uid())
    with check (user_id = auth.uid());
