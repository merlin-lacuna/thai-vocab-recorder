#!/usr/bin/env python3
"""Plans today's quiz email. Runs shortly after midnight (Asia/Bangkok) and after a boot.

The send time per weekday is in the Supabase table quiz_schedule. This planner asks the
Edge Function for today's plan and creates a one-time systemd timer for the send time.
That timer starts the mail job thai-quiz-mailer.service (send_quiz.py).

The work has two steps, which run with different rights (see thai-quiz-planner.service):

  plan_quiz.py fetch <file>   runs as the user "thaiquiz"
      GET <QUIZ_FUNCTION_URL>?action=plan, decide what to do, write the decision to <file>.
      This step uses the network and the send code. It has no rights over systemd.

  plan_quiz.py apply <file>   runs as root
      Read the decision, check its form, and call systemd. This step uses no network.
      It accepts three actions only: nothing, start the mail service now, or create the
      one-time timer "thai-quiz-send-today" for one checked UTC time.

The planner writes one log line per run and exits with status 1 on an error.
Only the Python standard library is used.
"""

import json
import os
import re
import subprocess
import sys
from datetime import datetime, timezone
from zoneinfo import ZoneInfo

import send_quiz

TIMER_UNIT = "thai-quiz-send-today"
MAIL_SERVICE = "thai-quiz-mailer.service"
SYSTEMCTL = "/usr/bin/systemctl"
SYSTEMD_RUN = "/usr/bin/systemd-run"
AT_UTC_FORMAT = "%Y-%m-%d %H:%M:%S"
AT_UTC_PATTERN = re.compile(r"^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$")


def send_moment_utc(date, send_time, time_zone):
    """The send moment as UTC. Example: "2026-11-17", "07:00", "Asia/Bangkok" -> 2026-11-17 00:00 UTC."""
    local = datetime.strptime(f"{date} {send_time[:5]}", "%Y-%m-%d %H:%M").replace(tzinfo=ZoneInfo(time_zone))
    return local.astimezone(timezone.utc)


def decide(plan, now_utc):
    """Turns the plan from the Edge Function into a decision: {"action", "at_utc", "log"}."""
    day = f"{plan.get('day_name', '')} {plan.get('date', '')}".strip()
    if not plan.get("send"):
        return {"action": "none", "at_utc": None, "log": f"no email today ({day}): the schedule has no quiz for this day"}
    if plan.get("already_sent"):
        return {"action": "none", "at_utc": None, "log": f"no email today ({day}): today's quiz was already sent"}
    moment = send_moment_utc(plan["date"], plan["send_time"], plan["time_zone"])
    when = f"{plan['send_time'][:5]} {plan['time_zone']} = {moment.strftime('%H:%M')} UTC"
    if moment <= now_utc:
        return {"action": "now", "at_utc": None, "log": f"starting now ({day}): the send time {when} has passed"}
    return {
        "action": "timer",
        "at_utc": moment.strftime(AT_UTC_FORMAT),
        "log": f"planned ({day}): quiz email at {when}",
    }


def fetch(path, env, now_utc=None):
    """Step 1 (user thaiquiz): asks for today's plan and writes the decision to [path]."""
    settings = {
        "QUIZ_SEND_TOKEN": env.get("QUIZ_SEND_TOKEN", "").strip(),
        "QUIZ_FUNCTION_URL": env.get("QUIZ_FUNCTION_URL", "").strip() or send_quiz.DEFAULT_FUNCTION_URL,
    }
    if not settings["QUIZ_SEND_TOKEN"]:
        raise ValueError("missing settings: QUIZ_SEND_TOKEN")
    plan = send_quiz.call_function(settings, "GET", {"action": "plan"})
    decision = decide(plan, now_utc or datetime.now(timezone.utc))
    with open(path, "w", encoding="utf-8") as file:
        json.dump(decision, file)


def read_decision(path):
    """Reads the decision file and rejects every form that step 1 does not write."""
    # O_NOFOLLOW: the file is written by the unprivileged step; a link is not accepted.
    descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
    with os.fdopen(descriptor, encoding="utf-8") as file:
        decision = json.loads(file.read(4000))
    action = decision.get("action")
    at_utc = decision.get("at_utc")
    if action not in ("none", "now", "timer"):
        raise ValueError(f"unknown action in the decision file: {action!r}")
    if action == "timer":
        if not isinstance(at_utc, str) or not AT_UTC_PATTERN.match(at_utc):
            raise ValueError("the decision file has no valid time for the timer")
        datetime.strptime(at_utc, AT_UTC_FORMAT)  # rejects e.g. month 13
    log = str(decision.get("log", ""))[:300].replace("\n", " ")
    return action, at_utc, log


def timer_commands(at_utc):
    """The systemd calls that replace the one-time timer with one for [at_utc]."""
    return [
        # Remove a timer from an earlier planner run of the same day. Fails when none exists.
        ([SYSTEMCTL, "stop", f"{TIMER_UNIT}.timer"], False),
        ([SYSTEMCTL, "reset-failed", f"{TIMER_UNIT}.timer", f"{TIMER_UNIT}.service"], False),
        ([
            SYSTEMD_RUN, "--quiet", f"--unit={TIMER_UNIT}",
            f"--on-calendar={at_utc} UTC",
            "--timer-property=AccuracySec=1s",
            "--timer-property=RemainAfterElapse=no",
            "--description=Start the Thai quiz mail job at today's send time",
            SYSTEMCTL, "start", MAIL_SERVICE,
        ], True),
    ]


def apply(path, run=subprocess.run):
    """Step 2 (root): reads the decision and calls systemd. Returns the log line."""
    action, at_utc, log = read_decision(path)
    if action == "now":
        run([SYSTEMCTL, "start", "--no-block", MAIL_SERVICE], check=True)
    elif action == "timer":
        for command, must_succeed in timer_commands(at_utc):
            run(command, check=must_succeed, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL if not must_succeed else None)
    return log


def main(argv):
    try:
        if len(argv) != 3 or argv[1] not in ("fetch", "apply"):
            raise ValueError("usage: plan_quiz.py fetch|apply <decision file>")
        if argv[1] == "fetch":
            fetch(argv[2], os.environ)
        else:
            print(apply(argv[2]))
    except Exception as error:  # one log line for every kind of failure
        print(f"error: {type(error).__name__}: {error}")
        sys.exit(1)


if __name__ == "__main__":
    main(sys.argv)
