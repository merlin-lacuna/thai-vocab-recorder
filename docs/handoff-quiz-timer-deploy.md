# Hand-over: automatic deploy of the quiz page (and the new timed quiz page)

For: the Claude Code instance on the user's computer that has the Hetzner skill and CLI.
Written by: the cloud session (2026-11-16). Earlier hand-overs:
`docs/handoff-quiz-page-hetzner.md` (you put `web/quiz/index.html` on the server at
`https://new.merlincarter.com/quiz/`) and `docs/handoff-quiz-mailer-hetzner.md`.

## Background

The quiz page `web/quiz/index.html` has a new version: the quiz email now has a
"Start" button, and the page measures how long the user takes to answer. The new email
links to `https://new.merlincarter.com/quiz/?q=<quiz id>` (no `c=`). The old page version
shows an error for that address, so the new page must be on the server **before the next
quiz email at 07:00 Asia/Bangkok**.

From now on, a GitHub Actions workflow copies the folder `web/` to the server whenever a
push changes it: `.github/workflows/deploy-web.yml` (already in the repository). Read the
workflow file first. It needs five repository secrets, which you set up.

## Your task

1. `git pull` on branch `claude/elegant-gates-odn9kz`.
2. **Deploy user on the server.** Create a separate SSH user (for example `deploy`) that
   can write only to the web folder that contains `quiz/` (the folder you used in the
   first hand-over). The deploy user needs no sudo rights and no other write access.
   The workflow runs `tar -C <DEPLOY_PATH> --no-same-owner -xf -` over SSH, so the user
   needs a shell and `tar`. Make sure the web server can still read the files that the
   deploy user writes (owner, group, mode).
3. **Key pair.** Create a new ed25519 key pair only for this workflow (no passphrase).
   Put the public key into the deploy user's `authorized_keys`. Optional but good: restrict
   the key in `authorized_keys` (for example `restrict` plus a forced command that only
   runs the tar command above).
4. **Repository secrets** in `merlin-lacuna/thai-vocab-recorder` (with `gh secret set` on
   the user's computer):
   - `DEPLOY_HOST`: server name or IP address
   - `DEPLOY_USER`: the deploy user
   - `DEPLOY_PATH`: the folder that contains `quiz/`
   - `DEPLOY_SSH_KEY`: the private key
   - `DEPLOY_KNOWN_HOSTS`: output of `ssh-keyscan -t ed25519 <DEPLOY_HOST>`
   Then delete the private key file from the user's computer; GitHub keeps the only copy.
   Never commit or print the private key: the repository is **public**.
5. **Run the workflow** by hand: `gh workflow run deploy-web.yml --ref claude/elegant-gates-odn9kz`,
   then `gh run watch`. The run must finish green.
6. **Test the new page** on the live server in a browser (or with Playwright):
   - `https://new.merlincarter.com/quiz/?q=adfbc402-a133-42bc-9e5d-d94f4bb99b76`
     must show the English sentence "Yesterday, I went to see my godson.", a running
     timer, and six Thai sentences as buttons. Tap the 4th sentence
     (เมื่อวานฉันไปหาลูกทูนหัว): the page must show "ถูกต้อง!" and "Answered in … s".
   - The old address form `...&c=3` must still show "ถูกต้อง!" (for old emails).
   - Each tap writes one row into `quiz_events`. Tell the user how many taps you made,
     so the test rows can be deleted.
7. Remove the manual copy instructions from `server/quiz-mailer/README.md` or wherever you
   wrote them, if they describe copying the quiz page by hand; replace them with one line:
   "The quiz page is deployed by .github/workflows/deploy-web.yml." Commit and push.

## Report to the user

One short message they can paste into the cloud session: the workflow run result, the
test result, and the number of test taps.

## Out of scope for you

- Changes to `web/quiz/index.html`, the Supabase function or the database: describe the
  need to the user instead.
