# Quiz mailer

A scheduled job on the Hetzner server. Once a day it sends the waiting Thai sentence quiz
by email.

## What the job does

1. It asks the Supabase Edge Function `daily-quiz` for the quiz that waits
   (`GET ?action=next`). A quiz waits when its row in `email_quizzes` has `sent_at = null`.
2. If no quiz waits, the job writes `nothing to send` to the log and stops with status 0.
3. Otherwise it sends one email with a plain-text part and an HTML part (both UTF-8)
   through Google Workspace: `smtp.gmail.com`, port 587, STARTTLS.
4. After the mail server accepted the email, the job marks the quiz as sent
   (`POST ?action=sent&q=<id>`). If sending fails, the job skips this call, so the next
   run sends the same quiz.

The job creates no quizzes.

## Files

| File | Place on the server | Purpose |
|---|---|---|
| `send_quiz.py` | `/opt/thai-quiz/send_quiz.py` | The job. Python 3, standard library only. |
| `thai-quiz-mailer.service` | `/etc/systemd/system/` | Runs the job once, as the user `thaiquiz`. |
| `thai-quiz-mailer.timer` | `/etc/systemd/system/` | Starts the service every day at 07:00 Asia/Bangkok. `Persistent=true`: a run that was missed while the server was off runs at the next start. |
| `env.example` | `/etc/thai-quiz/env` (the real file) | The settings. The real file holds secrets and exists only on the server. |
| `test_send_quiz.py` | – | Unit tests. They send no email and call no server. |

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
install -m 644 send_quiz.py /opt/thai-quiz/send_quiz.py
install -d -m 750 -o thaiquiz -g thaiquiz /etc/thai-quiz
install -m 600 -o thaiquiz -g thaiquiz env.example /etc/thai-quiz/env
nano /etc/thai-quiz/env        # fill in the values
install -m 644 thai-quiz-mailer.service thai-quiz-mailer.timer /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now thai-quiz-mailer.timer
```

## Update the script later

From this folder on the user's computer (`hetzner` is the SSH name of the server):

```bash
scp send_quiz.py hetzner:/opt/thai-quiz/send_quiz.py
```

## Run and check

| Task | Command (on the server) |
|---|---|
| Run the job now | `systemctl start thai-quiz-mailer.service` |
| Read the log | `journalctl -u thai-quiz-mailer.service -n 20 --no-pager` |
| See the next run time | `systemctl list-timers thai-quiz-mailer.timer` |

Each run writes one log line: `sent quiz <id> ...`, `nothing to send`, or `error: ...`.
After an error the service has the state "failed" until the next successful run.

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
