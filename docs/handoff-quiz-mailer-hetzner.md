# Hand-over: daily quiz emailer on the Hetzner server

For: a Claude Code instance on the user's computer that has the Hetzner skill and CLI.
Written by: the cloud session (2026-11-15). Read `docs/handoff-quiz-page-hetzner.md`
first: the answer page from that task is live at `https://new.merlincarter.com/quiz/`.

## Background

Quiz emails are rows in the Supabase table `email_quizzes`. A row with `sent_at = null`
is waiting to be sent. The Edge Function `daily-quiz` builds the email (HTML and plain
text) and marks a quiz as sent. Your job: a scheduled job on the Hetzner server that
sends the waiting quiz once a day by email.

The job creates no quizzes. Automatic quiz creation is a later, separate task. When no
quiz is waiting, the job sends nothing and exits with status 0.

## The function (already deployed and tested)

Base address: `https://vazojhdllcilzbdtavww.supabase.co/functions/v1/daily-quiz`

Every request needs the header `x-quiz-send-token: <QUIZ_SEND_TOKEN>`. Without it the
function answers 403.

1. `GET ?action=next` returns either
   `{"quiz": null}` (nothing to send) or
   `{"quiz": {"id": "...", "subject": "Thai Sentence Quiz - 11/15/2026", "html": "...", "text": "..."}}`
2. `POST ?action=sent&q=<id>` sets `sent_at` and returns `{"id": "...", "sent_at": "..."}`.

Call 2 only **after** the mail server accepted the email. If sending fails, do not call
it: the same quiz is then sent on the next run.

## The send code (QUIZ_SEND_TOKEN)

The user has the value in a file `quiz_send_token.txt` (sent to them by the cloud
session). Ask the user for that file. Rules:
- Store it only on the server, in a file readable only by the job user (mode 600),
  for example `/etc/thai-quiz/env`.
- Never commit it: the repository is **public**. Never print it in full in messages.
- The same rule applies to the SMTP password.

## Your task

1. `git pull` on branch `claude/elegant-gates-odn9kz`.
2. **Mail route (decided): the user's Google Workspace account on `merlincarter.com`.**
   - Send through `smtp.gmail.com`, port 587, STARTTLS, logging in with the user's
     Workspace address and a Google **app password** (needs 2-Step Verification on the
     account). Hetzner Cloud blocks outgoing ports 25 and 465; port 587 works. Do not set
     up a mail server.
   - Give the user the exact steps to create the app password (myaccount.google.com →
     Security → 2-Step Verification → App passwords). The user types it into the server
     env file themselves; they must not paste it in a chat.
   - The `From` address must be the Workspace account itself or an alias of it that is
     set up in Gmail ("Send mail as"); Google rewrites other `From` addresses.
   - DKIM: check that the DNS record `google._domainkey.merlincarter.com` exists. If not,
     tell the user to switch on DKIM in the Google Admin console (Apps → Google Workspace
     → Gmail → Authenticate email) and add the record it shows.
3. **Ask the user** for: the sender address (the Workspace address, or an alias such as
   `quiz@merlincarter.com` if they want one), and the time of day and time zone for the
   email. Recipient: the user's Gmail address (the same address that receives the
   ThaiPod101 emails; ask the user if you do not know it).
4. **Write the job** as a small Python 3 script with only the standard library
   (`urllib.request`, `smtplib`, `email.message`):
   - read the settings from the env file: `QUIZ_SEND_TOKEN`, `SMTP_HOST`, `SMTP_PORT`,
     `SMTP_USER`, `SMTP_PASS`, `MAIL_FROM`, `MAIL_TO`;
   - `GET ?action=next`; if `quiz` is null, exit 0;
   - build one email with a plain-text part (`text`) and an HTML part (`html`), both
     UTF-8 (the content is Thai), subject from `subject`, a `Date` and a `Message-ID`
     header on the sender domain;
   - send with STARTTLS on port 587; then `POST ?action=sent&q=<id>`;
   - write one log line per run (sent / nothing to send / error) and exit non-zero on error.
   - Put the script in this repository at `server/quiz-mailer/send_quiz.py`, with a
     `server/quiz-mailer/README.md` (what it does, the env file names without values,
     install and test commands). Commit and push to `claude/elegant-gates-odn9kz`.
5. **Schedule it** with a systemd service + timer (`Persistent=true`, so a run that was
   missed while the server was off runs at the next start) at the user's time and time zone.
6. **Test:** one unsent test quiz is waiting now (id `ab19d731-af99-4afe-8d44-9dde84020404`).
   - Run the service once by hand (`systemctl start ...`). The email must arrive in the
     user's Gmail **inbox** (not spam). Ask the user to confirm this and to check that
     the sender shows correctly and the Thai text is readable.
   - Run it a second time: the log must say "nothing to send" and no email is sent.
   - In Gmail, "Show original" on the email must show `SPF: PASS` and `DKIM: PASS`.
     If one fails, fix the DNS records before you finish.

## Report to the user

One short message they can paste into the cloud session:
- the sender address and the mail route (provider / service),
- the send time and time zone,
- the test result (inbox yes/no, SPF and DKIM results),
- the command to run the job by hand and the command to read its log.

## Out of scope for you

- Changes to the Supabase function, tables or the answer page: describe the need to
  the user instead; the cloud session makes them.
- Creating quizzes.
