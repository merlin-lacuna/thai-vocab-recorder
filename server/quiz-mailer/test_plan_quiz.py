"""Tests for plan_quiz.py. Run:  python3 -m unittest -v  (in this folder)

The tests replace the network call and the systemd calls.
"""

import json
import os
import tempfile
import unittest
from datetime import datetime, timezone
from unittest import mock

import plan_quiz

PLAN = {
    "date": "2026-11-17", "day_name": "Tuesday", "time_zone": "Asia/Bangkok",
    "send": True, "send_time": "07:00", "already_sent": False,
}


def utc(hour, minute=0, day=16):
    return datetime(2026, 11, day, hour, minute, tzinfo=timezone.utc)


class TimeZoneTest(unittest.TestCase):
    def test_bangkok_0700_is_midnight_utc(self):
        self.assertEqual(
            datetime(2026, 11, 17, 0, 0, tzinfo=timezone.utc),
            plan_quiz.send_moment_utc("2026-11-17", "07:00", "Asia/Bangkok"),
        )

    def test_send_time_with_seconds_is_accepted(self):
        self.assertEqual(
            datetime(2026, 11, 17, 11, 30, tzinfo=timezone.utc),
            plan_quiz.send_moment_utc("2026-11-17", "18:30:00", "Asia/Bangkok"),
        )


class DecideTest(unittest.TestCase):
    def test_future_moment_gives_a_timer(self):
        decision = plan_quiz.decide(PLAN, now_utc=utc(17, 5))  # 00:05 in Bangkok
        self.assertEqual("timer", decision["action"])
        self.assertEqual("2026-11-17 00:00:00", decision["at_utc"])
        self.assertIn("07:00 Asia/Bangkok", decision["log"])

    def test_past_moment_starts_the_mail_job_now(self):
        decision = plan_quiz.decide(PLAN, now_utc=utc(3, 0, day=17))  # 10:00 in Bangkok
        self.assertEqual("now", decision["action"])
        self.assertIsNone(decision["at_utc"])
        self.assertIn("starting now", decision["log"])

    def test_no_quiz_on_this_weekday(self):
        decision = plan_quiz.decide(dict(PLAN, send=False), now_utc=utc(17, 5))
        self.assertEqual("none", decision["action"])
        self.assertIn("no email today", decision["log"])

    def test_already_sent(self):
        decision = plan_quiz.decide(dict(PLAN, already_sent=True), now_utc=utc(17, 5))
        self.assertEqual("none", decision["action"])
        self.assertIn("already sent", decision["log"])


class FetchAndApplyTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.path = os.path.join(self.folder.name, "plan.json")
        self.calls = []

    def tearDown(self):
        self.folder.cleanup()

    def run_command(self, command, **kwargs):
        self.calls.append(command)

    def write(self, decision):
        with open(self.path, "w", encoding="utf-8") as file:
            json.dump(decision, file)

    def test_fetch_writes_the_decision(self):
        with mock.patch.object(plan_quiz.send_quiz, "call_function", return_value=PLAN) as call:
            plan_quiz.fetch(self.path, {"QUIZ_SEND_TOKEN": "token-for-test"}, now_utc=utc(17, 5))
        self.assertEqual({"action": "plan"}, call.call_args.args[2])
        with open(self.path, encoding="utf-8") as file:
            self.assertEqual("timer", json.load(file)["action"])

    def test_fetch_needs_the_send_code(self):
        with self.assertRaisesRegex(ValueError, "QUIZ_SEND_TOKEN"):
            plan_quiz.fetch(self.path, {})

    def test_apply_timer_replaces_the_old_timer_and_creates_one(self):
        self.write({"action": "timer", "at_utc": "2026-11-17 00:00:00", "log": "planned"})
        self.assertEqual("planned", plan_quiz.apply(self.path, run=self.run_command))
        self.assertEqual(["/usr/bin/systemctl", "stop", "thai-quiz-send-today.timer"], self.calls[0])
        create = self.calls[-1]
        self.assertEqual("/usr/bin/systemd-run", create[0])
        self.assertIn("--unit=thai-quiz-send-today", create)
        self.assertIn("--on-calendar=2026-11-17 00:00:00 UTC", create)
        self.assertEqual(["/usr/bin/systemctl", "start", "thai-quiz-mailer.service"], create[-3:])

    def test_apply_now_starts_the_mail_service(self):
        self.write({"action": "now", "at_utc": None, "log": "starting now"})
        plan_quiz.apply(self.path, run=self.run_command)
        self.assertEqual([["/usr/bin/systemctl", "start", "--no-block", "thai-quiz-mailer.service"]], self.calls)

    def test_apply_none_calls_nothing(self):
        self.write({"action": "none", "at_utc": None, "log": "no email today"})
        self.assertEqual("no email today", plan_quiz.apply(self.path, run=self.run_command))
        self.assertEqual([], self.calls)

    def test_apply_rejects_other_actions_and_bad_times(self):
        for decision in (
            {"action": "reboot", "at_utc": None},
            {"action": "timer", "at_utc": "2026-11-17 00:00:00 UTC; rm -rf /"},
            {"action": "timer", "at_utc": "2026-13-40 00:00:00"},
            {"action": "timer", "at_utc": None},
        ):
            self.write(decision)
            with self.assertRaises(ValueError):
                plan_quiz.apply(self.path, run=self.run_command)
        self.assertEqual([], self.calls)


if __name__ == "__main__":
    unittest.main()
