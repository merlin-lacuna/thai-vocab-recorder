# ntfy push server (ntfy.merlincarter.com)

The Supabase function `daily-quiz` sends a UnifiedPush message to this server when it sends a
quiz email. The ntfy app on the phone keeps a connection to the server open and passes the message
to the quiz app (`quiz/`).

## What runs where

- Server: the Hetzner server `91.98.77.228` (SSH alias `hetzner`, user root), the same server
  as `new.merlincarter.com`.
- DNS: A and AAAA records for `ntfy` at IONOS, pointing to the server addresses.
- Container `ntfy`, image `binwiederhier/ntfy:v2.38.1`, started with Docker Compose from
  `/root/ntfy` (copies of `docker-compose.yml` and `server.yml` in this folder).
  - `/root/ntfy/server.yml`: configuration.
  - `/root/ntfy/lib/user.db`: users, passwords and access rules. Never copy into the repository.
  - `/root/ntfy/cache/cache.db`: messages for 12 hours.
- The container has no public port. Caddy (container `nagare-caddy`, configuration in
  `/root/nagare/Caddyfile`) handles HTTPS and forwards `ntfy.merlincarter.com` to `ntfy:80` on
  the Docker network `nagare_default`.

## Access rules

- Default for everyone: deny all.
- Anonymous clients: write only, topics `up*`. Supabase sends without a login; the topic names
  are long and random.
- User `merlin` (the phone): read and write, topics `up*`. The password is only in the ntfy app
  on the phone and in `user.db`.

Tests (2026-11-21): anonymous write to `up…` gives 200; anonymous read of `up…` gives 403;
read with the `merlin` login gives 200; anonymous write to another topic gives 403.

## Commands

Run on the server (`ssh hetzner`):

```sh
docker ps --filter name=^ntfy$                # is the container running
docker logs --tail 50 ntfy                    # recent log lines
curl -s https://ntfy.merlincarter.com/v1/health
docker exec ntfy ntfy user list               # users and access rules
docker exec -it ntfy ntfy user add NAME       # add a user (asks for the password)
docker exec -it ntfy ntfy user change-pass merlin
docker exec ntfy ntfy access NAME 'up*' rw    # give a user read-write access to up* topics
cd /root/ntfy && docker compose pull && docker compose up -d   # after a version change
```

## Changing the Caddyfile

`/root/nagare/Caddyfile` is mounted into the container as a single file. The container can stay
bound to an older copy of it (on 2026-11-21 it was). After an edit on the host, write the file
into the container in place as well, then validate and reload:

```sh
docker exec -i nagare-caddy sh -c 'cat > /etc/caddy/Caddyfile' < /root/nagare/Caddyfile
docker exec nagare-caddy caddy validate --config /etc/caddy/Caddyfile
docker exec nagare-caddy caddy reload --config /etc/caddy/Caddyfile
```
