# Hand-over: send time from Supabase instead of a fixed 07:00 timer

For: the Claude Code instance on the user's computer that has the Hetzner skill and CLI.
Written by: the cloud session (2026-11-16). Read `server/quiz-mailer/README.md` first: it
describes the mail job that you installed earlier.

## Background

Today the timer `thai-quiz-mailer.timer` starts the mail job every day at 07:00
Asia/Bangkok. The user wants to change the send time per weekday in Supabase, in the
table `quiz_schedule` (column `send_time`, local time Asia/Bangkok). The user does not
want a job that checks every few minutes.

The new design: a **planner** runs shortly after midnight, asks Supabase for today's send
time, and sets a one-time timer on the server for that time. The one-time timer starts the
existing mail service `thai-quiz-mailer.service`. A change of `send_time` after midnight
takes effect the next day; the user accepts this.

## The Supabase side (already deployed and tested)

Same base address and header as the mail job (`x-quiz-send-token`).

`GET ?action=plan` returns today's plan, for example:

    {"date": "2026-11-17", "day_name": "Tuesday", "time_zone": "Asia/Bangkok",
     "send": true, "send_time": "07:00", "already_sent": false}

- `send: false`: no quiz email today.
- `already_sent: true`: today's quiz email was already sent.
- `GET ?action=next` now also returns `{"quiz": null, "reason": "today's quiz was already sent"}`
  after the first email of a local day. So a second start of the mail service on the same
  day sends no second email. The mail script already handles `"quiz": null` (log line
  "nothing to send"); check that it still works when the answer also has a `reason` key,
  and write the reason into the log line.

## Your task

1. `git pull` on branch `claude/elegant-gates-odn9kz`.
2. **Planner script** `server/quiz-mailer/plan_quiz.py` (Python 3, standard library, same
   env file and settings as `send_quiz.py`; reuse its functions where it makes sense):
   - `GET ?action=plan`.
   - If `send` is false or `already_sent` is true: log one line and exit 0.
   - Otherwise build the local send moment from `date`, `send_time` and `time_zone`.
     - If the moment is in the future: create a one-time systemd timer for it that starts
       `thai-quiz-mailer.service` (for example with `systemd-run --on-calendar=...`).
       Use one fixed unit name (for example `thai-quiz-send-today`), and replace an
       existing timer with that name, so a second planner run on the same day does not
       create a second timer.
     - If the moment has passed (for example the server was off at that time): start
       `thai-quiz-mailer.service` now.
   - Log one line: the planned time, or "starting now", or the reason for no email.
   - The planner needs the right to create the timer. Choose the least privilege that
     works (for example a root-owned service that runs only this script, or a polkit rule
     for the `thaiquiz` user that allows only this). Explain the choice in the README.
3. **Planner units:** `thai-quiz-planner.service` (oneshot) and `thai-quiz-planner.timer`
   with `OnCalendar=*-*-* 00:05:00 Asia/Bangkok`, `Persistent=true`, and `OnBootSec=2min`
   (a restart deletes one-time timers; the boot run creates today's timer again).
4. **Remove the fixed 07:00 timer:** disable and delete `thai-quiz-mailer.timer` on the
   server and in the repository. `thai-quiz-mailer.service` stays as it is.
5. **Tests:**
   - Unit tests for the planner in `server/quiz-mailer/test_plan_quiz.py` (no network, no
     systemd calls): future moment → timer; past moment → start now; `send: false` and
     `already_sent: true` → nothing. Include a time zone check: "2026-11-17", "07:00",
     "Asia/Bangkok" must give 2026-11-17 00:00 UTC.
   - On the server: run the planner once now. Today's quiz was already sent, so the log
     must say so and no timer may exist (`systemctl list-timers 'thai-quiz-send*'`).
   - The first real run is tonight at 00:05 Bangkok time. Tell the user the command to
     check tomorrow that the planner created the timer and that the email was sent.
6. Update `server/quiz-mailer/README.md` (what runs when, files, commands), then commit and
   push to `claude/elegant-gates-odn9kz`.

## Report to the user

One short message: test results, the least-privilege choice, and the command to check
tomorrow's run.

## Out of scope for you

- Changes to the Supabase function or tables, and to `web/`: describe the need to the user.
