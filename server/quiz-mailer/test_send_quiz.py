"""Tests for send_quiz.py. Run:  python3 -m unittest -v  (in this folder)

The tests replace the network calls, so they send no email and call no server.
"""

import unittest
from unittest import mock

import send_quiz

ENV = {
    "QUIZ_SEND_TOKEN": "token-for-test",
    "SMTP_HOST": "smtp.example.com",
    "SMTP_PORT": "587",
    "SMTP_USER": "sender@example.com",
    "SMTP_PASS": "abcd efgh ijkl mnop",
    "MAIL_FROM": "sender@example.com",
    "MAIL_TO": "learner@example.org",
}
QUIZ = {
    "id": "quiz-1",
    "subject": "Thai Sentence Quiz - 11/15/2026",
    "html": "<p>เมื่อวานฉันไปหาลูกทูนหัว</p>",
    "text": "เมื่อวานฉันไปหาลูกทูนหัว",
}


class SettingsTest(unittest.TestCase):
    def test_missing_settings_are_named(self):
        env = dict(ENV, SMTP_PASS="", MAIL_TO="  ")
        with self.assertRaisesRegex(ValueError, "SMTP_PASS, MAIL_TO"):
            send_quiz.load_settings(env)

    def test_spaces_are_removed_from_the_app_password(self):
        self.assertEqual("abcdefghijklmnop", send_quiz.load_settings(ENV)["SMTP_PASS"])

    def test_port_is_a_number_and_the_function_url_has_a_default(self):
        settings = send_quiz.load_settings(ENV)
        self.assertEqual(587, settings["SMTP_PORT"])
        self.assertEqual(send_quiz.DEFAULT_FUNCTION_URL, settings["QUIZ_FUNCTION_URL"])


class MessageTest(unittest.TestCase):
    def setUp(self):
        self.message = send_quiz.build_message(QUIZ, "sender@example.com", "learner@example.org")

    def test_headers(self):
        self.assertEqual(QUIZ["subject"], self.message["Subject"])
        self.assertEqual("sender@example.com", self.message["From"])
        self.assertEqual("learner@example.org", self.message["To"])
        self.assertTrue(self.message["Date"])
        self.assertTrue(self.message["Message-ID"].endswith("@example.com>"))

    def test_plain_part_and_html_part_are_utf8_with_the_thai_text(self):
        plain, html = self.message.iter_parts()
        self.assertEqual("text/plain", plain.get_content_type())
        self.assertEqual("text/html", html.get_content_type())
        self.assertEqual("utf-8", plain.get_content_charset())
        self.assertEqual("utf-8", html.get_content_charset())
        self.assertIn("ลูกทูนหัว", plain.get_content())
        self.assertIn("ลูกทูนหัว", html.get_content())


class RunTest(unittest.TestCase):
    def test_no_quiz_sends_no_email(self):
        with mock.patch.object(send_quiz, "call_function", return_value={"quiz": None}) as call, \
                mock.patch.object(send_quiz, "send_email") as send:
            self.assertEqual("nothing to send", send_quiz.run(ENV))
        send.assert_not_called()
        self.assertEqual(1, call.call_count)

    def test_reason_from_the_function_is_in_the_log_line(self):
        answer = {"quiz": None, "reason": "today's quiz was already sent"}
        with mock.patch.object(send_quiz, "call_function", return_value=answer), \
                mock.patch.object(send_quiz, "send_email") as send:
            self.assertEqual("nothing to send (today's quiz was already sent)", send_quiz.run(ENV))
        send.assert_not_called()

    def test_quiz_is_sent_and_then_marked_as_sent(self):
        with mock.patch.object(send_quiz, "call_function", side_effect=[{"quiz": QUIZ}, {"id": "quiz-1"}]) as call, \
                mock.patch.object(send_quiz, "send_email") as send:
            line = send_quiz.run(ENV)
        self.assertIn("sent quiz quiz-1", line)
        send.assert_called_once()
        self.assertEqual(("POST", {"action": "sent", "q": "quiz-1"}), call.call_args_list[1].args[1:])

    def test_failed_email_does_not_mark_the_quiz_as_sent(self):
        with mock.patch.object(send_quiz, "call_function", return_value={"quiz": QUIZ}) as call, \
                mock.patch.object(send_quiz, "send_email", side_effect=OSError("connection refused")):
            with self.assertRaises(OSError):
                send_quiz.run(ENV)
        self.assertEqual(1, call.call_count)  # only action=next


if __name__ == "__main__":
    unittest.main()
