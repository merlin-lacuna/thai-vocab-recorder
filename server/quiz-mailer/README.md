# Quiz mailer

Two scheduled jobs on the Hetzner server. Together they send the Thai sentence quiz by
email once a day, at the send time that the Supabase table `quiz_schedule` gives for the
weekday.

## What runs when

| When | Unit | What it does |
|---|---|---|
| 00:05 Asia/Bangkok every day, and 2 minutes after a boot | `thai-quiz-planner.timer` → `thai-quiz-planner.service` | The **planner** (`plan_quiz.py`) asks Supabase for today's plan and creates a one-time timer for today's send time. |
| Today's send time | `thai-quiz-send-today.timer` (one-time, created by the planner) | Starts `thai-quiz-mailer.service`. |
| When started | `thai-quiz-mailer.service` | The **mail job** (`send_quiz.py`) sends the waiting quiz. |

A change of `send_time` in Supabase after 00:05 takes effect on the next day.

### The planner (`plan_quiz.py`)

1. It asks the Edge Function `daily-quiz` for today's plan (`GET ?action=plan`). The answer
   holds the local date, the time zone, `send` (quiz email today or not), `send_time`
   and `already_sent`.
2. `send` is false, or `already_sent` is true: the planner writes the reason to the log
   and stops with status 0.
3. The send time is in the future: the planner creates the one-time timer
   `thai-quiz-send-today` for that moment. A timer of the same name from an earlier run is
   replaced, so two planner runs on one day leave one timer.
4. The send time has passed (for example, the server was off): the planner starts the
   mail job at once.

A restart of the server deletes the one-time timer. The planner run after the boot creates
it again.

### The mail job (`send_quiz.py`)

1. It asks the Edge Function for the quiz that waits (`GET ?action=next`).
2. If no quiz waits, it writes `nothing to send` and the reason from the function to the
   log, and stops with status 0. After the first email of a local day, the function
   answers "today's quiz was already sent", so a second start on the same day sends no
   second email.
3. Otherwise it sends one email with a plain-text part and an HTML part (both UTF-8)
   through Google Workspace: `smtp.gmail.com`, port 587, STARTTLS.
4. After the mail server accepted the email, the job marks the quiz as sent
   (`POST ?action=sent&q=<id>`). If sending fails, the job skips this call, so the next
   run sends the same quiz.

## Rights of the planner

Creating a systemd timer needs root rights. Calling Supabase does not. So the planner
service runs its two steps with different rights:

| Step | Runs as | Uses | Has no access to |
|---|---|---|---|
| `plan_quiz.py fetch` | user `thaiquiz` | the network and the send code | systemd |
| `plan_quiz.py apply` | root (`ExecStartPost=+…`) | systemd | the network |

Step 1 writes its decision into the file `/run/thai-quiz/plan.json`. Step 2 reads that
file and accepts three actions only: nothing, start `thai-quiz-mailer.service` now, or
create the timer `thai-quiz-send-today` for one UTC time. It checks the form of the time
before it calls systemd. Data from the internet therefore never runs as root, and the
`thaiquiz` user needs no polkit rule and no sudo rights. The script files in
`/opt/thai-quiz/` belong to root, so the `thaiquiz` user cannot change what step 2 runs.

## Files

| File | Place on the server | Purpose |
|---|---|---|
| `send_quiz.py` | `/opt/thai-quiz/` | The mail job. Python 3, standard library only. |
| `plan_quiz.py` | `/opt/thai-quiz/` | The planner. Uses functions of `send_quiz.py`. |
| `thai-quiz-mailer.service` | `/etc/systemd/system/` | Runs the mail job once, as the user `thaiquiz`. |
| `thai-quiz-planner.service` | `/etc/systemd/system/` | Runs the two planner steps once. |
| `thai-quiz-planner.timer` | `/etc/systemd/system/` | Starts the planner at 00:05 Asia/Bangkok and after a boot. `Persistent=true`: a missed run happens at the next start. |
| `env.example` | `/etc/thai-quiz/env` (the real file) | The settings. The real file holds secrets and exists only on the server. |
| `test_send_quiz.py`, `test_plan_quiz.py` | – | Unit tests. They send no email, call no server and call no systemd. |

## Settings (`/etc/thai-quiz/env`)

| Name | Content |
|---|---|
| `QUIZ_SEND_TOKEN` | The send code that the Edge Function checks (header `x-quiz-send-token`). |
| `SMTP_HOST`, `SMTP_PORT` | `smtp.gmail.com`, `587` |
| `SMTP_USER` | The Google Workspace address that sends the email. |
| `SMTP_PASS` | A Google **app password** for that address (needs 2-Step Verification). |
| `MAIL_FROM` | The same address as `SMTP_USER`. Google rewrites other `From` addresses. |
| `MAIL_TO` | The address that receives the quiz. |
| `QUIZ_FUNCTION_URL` | Optional. Default: the `daily-quiz` function of this project. |

The file belongs to the user `thaiquiz` and has mode 600. Never commit the real file:
this repository is public.

To create an app password: <https://myaccount.google.com> → **Security** →
**2-Step Verification** → **App passwords**. Type it into the file on the server.

## Install

Run these commands on the server as root, in a copy of this folder:

```bash
useradd --system --no-create-home --shell /usr/sbin/nologin thaiquiz
install -d -m 755 /opt/thai-quiz
install -m 644 send_quiz.py plan_quiz.py /opt/thai-quiz/
install -d -m 750 -o thaiquiz -g thaiquiz /etc/thai-quiz
install -m 600 -o thaiquiz -g thaiquiz env.example /etc/thai-quiz/env
nano /etc/thai-quiz/env        # fill in the values
install -m 644 thai-quiz-mailer.service thai-quiz-planner.service thai-quiz-planner.timer /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now thai-quiz-planner.timer
```

## Update the scripts later

From this folder on the user's computer (`hetzner` is the SSH name of the server):

```bash
scp send_quiz.py plan_quiz.py hetzner:/opt/thai-quiz/
```

## Run and check

| Task | Command (on the server) |
|---|---|
| See today's one-time timer and the next planner run | `systemctl list-timers 'thai-quiz*' --all` |
| Read the planner log | `journalctl -u thai-quiz-planner.service -n 20 --no-pager` |
| Read the mail job log | `journalctl -u thai-quiz-mailer.service -n 20 --no-pager` |
| Run the planner now | `systemctl start thai-quiz-planner.service` |
| Run the mail job now | `systemctl start thai-quiz-mailer.service` |

The planner writes one log line per run: `planned (...)`, `starting now (...)`,
`no email today (...)`, or `error: ...`. The mail job writes `sent quiz <id> ...`,
`nothing to send (...)`, or `error: ...`. After an error a service has the state "failed"
until its next successful run.

## Quiz page on the same server

The quiz page is deployed by .github/workflows/deploy-web.yml.

| Item | Value |
|---|---|
| Address | `https://new.merlincarter.com/quiz/` |
| Folder on the server | `/var/www/thai-vocab/` (the workflow copies `web/` into it). `/var/www/quiz` is a link to `thai-vocab/quiz`, which the Caddy web server serves. |
| SSH user | `deploy`: owns only `/var/www/thai-vocab/`, without sudo rights. Its key runs one fixed command (`tar -C /var/www/thai-vocab --no-same-owner -xf -`) and nothing else. |
| Secrets | Five repository secrets `DEPLOY_*` in GitHub. GitHub holds the only copy of the private key. |

## Tests

```bash
python3 -m unittest -v
```
