# Hand-over: push server (ntfy) for the quiz app

For: the Claude Code instance on the user's computer that has the Hetzner skill and CLI.
Written by: the cloud session (2026-11-21).

## Background

The new Android app "Thai Quiz" (`quiz/` in this repository) shows a notification when a quiz
email is sent. The message goes through a push server on the user's Hetzner server, with the
open UnifiedPush standard (no Google Firebase):

1. The Supabase Edge Function `daily-quiz` marks a quiz as sent and then sends an HTTPS POST
   to each address in the table `push_endpoints`, for example
   `https://ntfy.<domain>/upAbC123...?up=1`, body `{"type":"quiz","id":"..."}`.
2. The ntfy server receives the POST and passes it to the ntfy app on the phone, which keeps
   a connection open to the server.
3. The ntfy app passes the message to the quiz app, which shows its own notification.

The quiz app gets its address from the ntfy app and saves it in Supabase itself.

## Your task

1. Ask the user which subdomain to use (suggestion: `ntfy.merlincarter.com`) and create or ask
   for the DNS record that points to the server.
2. Install the ntfy server (https://docs.ntfy.sh/install/) on the server that already hosts
   `new.merlincarter.com`. Add it to the existing web server (reverse proxy with HTTPS); do not
   replace the existing configuration. Follow https://docs.ntfy.sh/config/#behind-a-proxy-tls-etc
   (`base-url`, `behind-proxy: true`, WebSocket upgrade allowed for `/…/ws`).
3. Access rules (https://docs.ntfy.sh/config/#access-control):
   - `auth-default-access: deny-all`.
   - Anonymous **write-only** access to topics `up*` (UnifiedPush topics). Supabase sends
     without a login. The topic names are long and random.
   - One user for the phone (for example `merlin`) with read-write access to `up*`. Give the
     password to the user only on the screen of their computer, never in a file in the
     repository.
4. Tests:
   - `curl -d test https://ntfy.<domain>/uptest$RANDOM` without login: must succeed (HTTP 200).
   - `curl https://ntfy.<domain>/uptest123/json?poll=1` without login: must be refused (HTTP 403).
   - Same request with the phone user's login: must succeed.
   - A topic that does not start with `up`, without login: write must be refused.
5. Write `server/ntfy/README.md` (what runs, config file location without secrets, the commands
   to add a user or check the service) and commit and push to `claude/elegant-gates-odn9kz`.
   Do not commit passwords or the user database.

## Phone set-up: give the user these steps

1. Install "ntfy" from Google Play.
2. ntfy app → Settings → General → Default server: `https://ntfy.<domain>`.
3. ntfy app → Settings → Users → Add user: server `https://ntfy.<domain>`, the phone user and
   password.
4. Install the quiz app (GitHub release "latest", file `thai-vocab-quiz.apk`), log in, and
   allow notifications. The quiz app registers with ntfy by itself.

## Report to the user

One short message they can paste into the cloud session: the ntfy address and the test results.
The cloud session then checks that the quiz app saved its address and sends a test notification.

## Out of scope for you

- The quiz app and the Supabase function: describe any needed change to the user instead.
