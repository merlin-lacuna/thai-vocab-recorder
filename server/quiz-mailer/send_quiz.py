#!/usr/bin/env python3
"""Sends the waiting Thai sentence quiz by email. Runs once a day from a systemd timer.

Steps:
  1. GET  <QUIZ_FUNCTION_URL>?action=next       -> the quiz that waits, or {"quiz": null}
  2. send one email (plain-text part and HTML part) through SMTP with STARTTLS
  3. POST <QUIZ_FUNCTION_URL>?action=sent&q=<id> -> marks the quiz as sent

Step 3 runs only after the mail server accepted the email. If sending fails, the same
quiz is sent on the next run.

Settings come from environment variables (systemd reads them from /etc/thai-quiz/env):
  QUIZ_SEND_TOKEN, SMTP_HOST, SMTP_PORT, SMTP_USER, SMTP_PASS, MAIL_FROM, MAIL_TO
Optional: QUIZ_FUNCTION_URL.

The script writes one log line per run and exits with status 1 on an error.
Only the Python standard library is used.
"""

import json
import os
import smtplib
import sys
import urllib.error
import urllib.parse
import urllib.request
from email.message import EmailMessage
from email.utils import formatdate, make_msgid

DEFAULT_FUNCTION_URL = "https://vazojhdllcilzbdtavww.supabase.co/functions/v1/daily-quiz"
REQUIRED = ["QUIZ_SEND_TOKEN", "SMTP_HOST", "SMTP_PORT", "SMTP_USER", "SMTP_PASS", "MAIL_FROM", "MAIL_TO"]


def load_settings(env):
    """Returns the settings as a dict. Raises ValueError and names the missing variables."""
    missing = [name for name in REQUIRED if not env.get(name, "").strip()]
    if missing:
        raise ValueError("missing settings: " + ", ".join(missing))
    settings = {name: env[name].strip() for name in REQUIRED}
    settings["SMTP_PORT"] = int(settings["SMTP_PORT"])
    # Google shows an app password in four groups with spaces. SMTP needs it without them.
    settings["SMTP_PASS"] = settings["SMTP_PASS"].replace(" ", "")
    settings["QUIZ_FUNCTION_URL"] = env.get("QUIZ_FUNCTION_URL", "").strip() or DEFAULT_FUNCTION_URL
    return settings


def build_message(quiz, mail_from, mail_to):
    """Builds the email: a plain-text part and an HTML part, both UTF-8 (the content is Thai)."""
    message = EmailMessage()
    message["Subject"] = quiz["subject"]
    message["From"] = mail_from
    message["To"] = mail_to
    message["Date"] = formatdate(localtime=True)
    message["Message-ID"] = make_msgid(domain=mail_from.rsplit("@", 1)[-1].rstrip(">"))
    message.set_content(quiz["text"], subtype="plain", charset="utf-8")
    message.add_alternative(quiz["html"], subtype="html", charset="utf-8")
    return message


def call_function(settings, method, query):
    """Calls the Edge Function and returns the JSON answer."""
    url = settings["QUIZ_FUNCTION_URL"] + "?" + urllib.parse.urlencode(query)
    request = urllib.request.Request(
        url,
        method=method,
        data=b"" if method == "POST" else None,
        headers={"x-quiz-send-token": settings["QUIZ_SEND_TOKEN"], "Accept": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        body = error.read().decode("utf-8", "replace")[:200]
        raise RuntimeError(f"function answered HTTP {error.code} for action={query.get('action')}: {body}") from None


def send_email(settings, message):
    with smtplib.SMTP(settings["SMTP_HOST"], settings["SMTP_PORT"], timeout=60) as smtp:
        smtp.starttls()
        smtp.login(settings["SMTP_USER"], settings["SMTP_PASS"])
        smtp.send_message(message)


def run(env):
    """One run. Returns the log line."""
    settings = load_settings(env)
    quiz = call_function(settings, "GET", {"action": "next"}).get("quiz")
    if quiz is None:
        return "nothing to send"
    message = build_message(quiz, settings["MAIL_FROM"], settings["MAIL_TO"])
    send_email(settings, message)
    call_function(settings, "POST", {"action": "sent", "q": quiz["id"]})
    return f"sent quiz {quiz['id']} ({quiz['subject']}) to {settings['MAIL_TO']}"


def main():
    try:
        print(run(os.environ))
    except Exception as error:  # one log line for every kind of failure
        print(f"error: {type(error).__name__}: {error}")
        sys.exit(1)


if __name__ == "__main__":
    main()
