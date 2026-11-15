# Hand-over: put the quiz answer page on the Hetzner server

For: a Claude Code instance on the user's computer that has the Hetzner skill and CLI.
Written by: the cloud session that built the quiz email (2026-11-15).

## Background

The user receives a quiz email. The email shows one English sentence with one word
highlighted, and six Thai sentences as buttons. Each button is a link to the **answer page**:

    <PAGE_URL>?q=<quiz id>&c=<choice index 0-5>

The answer page is one static file: `web/quiz/index.html` in this repository. It contains
no secrets and no data. In the browser, it calls the Supabase Edge Function
`https://vazojhdllcilzbdtavww.supabase.co/functions/v1/daily-quiz` (CORS allows every
origin; no login), which records the answer and returns the quiz data as JSON.

Supabase cannot host the page itself: on its free addresses it changes `text/html` to
`text/plain`. That is why the page must be on the Hetzner server.

## Your task

1. Get the file: `git pull` on branch `claude/elegant-gates-odn9kz`. The file is
   `web/quiz/index.html`.
2. With the Hetzner skill, choose an existing server of the user (ask the user which one
   if there is more than one; do not create a new paid server without asking).
3. Serve the file over **HTTPS** at a stable address that ends in `/quiz/`, for example
   `https://<domain or subdomain>/quiz/`. Requirements:
   - HTTPS with a valid certificate (email apps on Android open links in a browser that
     warns about plain HTTP). Caddy with automatic certificates is the simplest way if no
     web server runs yet. If a web server (nginx, Caddy, Traefik ...) already runs, add
     the file to it; do not replace the existing configuration.
   - `Content-Type: text/html; charset=utf-8` for the file.
   - The query string (`?q=...&c=...`) must reach the page unchanged (no redirect that
     removes it).
   - If the server has no domain name, ask the user for a domain/subdomain and the DNS
     record to create. A bare IP address cannot get a normal HTTPS certificate.
4. Test in a browser or with Playwright. This address must show the green heading
   "ถูกต้อง!" and the word ลูกทูนหัว with "No native recording yet":

       https://<your address>/quiz/?q=adfbc402-a133-42bc-9e5d-d94f4bb99b76&c=3

   And `...&c=1` must show the red heading "ยังไม่ถูก" and five links.
   Note: each test opening writes one row into the table `quiz_events` (quiz_type
   `email_sentence`). Tell the user how many times you opened the page, so the test
   rows can be deleted.
5. Report back to the user, in one short message they can paste into the cloud session:
   - the final page address (ending in `/quiz/`),
   - the number of test openings.

## Out of scope for you

- Setting the Supabase secret `QUIZ_PAGE_URL` and sending the email: the cloud session
  does this after the user gives it the address.
- Changes to `web/quiz/index.html`: if the page needs a change, describe it to the user
  instead, so both copies stay the same.

## Updating the page later

When `web/quiz/index.html` changes in the repository, copy the new file to the same place
on the server. Write the exact copy command into your report, so the user can run it later.
