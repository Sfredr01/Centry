# Centry

Centry is a family expense tracker and budget guard. It runs as one Spring Boot container on a Raspberry Pi
at home, and you use it from a phone or laptop.

- Quick-add form, installable as a home-screen app (PWA), for logging a purchase in a few taps.
- Monthly budgets per category, with a bar on the dashboard.
- One-time WARN (at the warning percent, default 80%) and EXCEEDED (at 100%) alerts per category and month, sent
  to ntfy or a Discord/Slack-style webhook. Failed deliveries are retried for up to 24 hours.
- Who spent what: each expense is attributed to a household member, and the dashboard filters by member.
- Roles: an admin manages members and budgets. A member adds expenses and edits or deletes only the ones they
  created. Members without a password can be picked as the spender but cannot log in.
- Optional Kafka ingest, off by default.

Centry is for a home LAN or a tailnet. Read [SECURITY.md](SECURITY.md) before you expose it anywhere.

## Requirements

- Raspberry Pi 4 or 5 with 2 GB RAM or more, running 64-bit Raspberry Pi OS.
- A USB SSD is recommended over an SD card. Centry writes to SQLite on every expense, and SD cards wear out
  under frequent small writes.
- Docker Engine with the Compose plugin:

```bash
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker "$USER"
# Log out and back in so the group change applies.
```

## Install

```bash
sudo mkdir -p /opt/centry && sudo chown "$USER" /opt/centry
git clone --depth 1 https://github.com/Sfredr01/Centry.git /opt/centry
cd /opt/centry
cp .env.example .env
chmod 600 .env
```

Edit `.env`:

1. Set `CENTRY_ADMIN_PASSWORD` to a passphrase of at least 12 characters.
2. Set `CENTRY_TAG` to a commit tag such as `sha-<40 hex characters>`. Find one on the repository's Packages
   page (`ghcr.io/sfredr01/centry`) or in the run summary of the Deploy workflow for the commit you want. Do not
   use `latest`: the tag is what makes upgrades and rollbacks predictable.
3. Set `TZ` to your household time zone, for example `America/Chicago`. It decides which month a budget counts
   an expense in; the app clock follows it.

Create the data directory, owned by the container user (uid and gid 10001), then start Centry:

```bash
sudo mkdir -p /opt/centry/data && sudo chown -R 10001:10001 /opt/centry/data
docker compose up -d
curl localhost:8080/actuator/health
```

The health check should print `{"status":"UP"}`. The database is `/opt/centry/data/centry.db`.

If the image is in a private package, run `docker login ghcr.io` on the Pi first.

## First login and household members

Open `http://<pi-address>:8080/` and sign in as `admin` (or `CENTRY_ADMIN_USERNAME`) with the password from
`.env`. The admin password is read only while no users exist, so changing it later does not change the account.

Add a member with the API. This example asks for the admin password instead of putting it on the command line:

```bash
curl -u admin -H 'Content-Type: application/json' \
  -d '{"username":"sam","displayName":"Sam","role":"MEMBER"}' \
  http://localhost:8080/api/v1/users
```

Leave out `password` to create a member who can be picked as a spender but cannot sign in. To let someone sign
in, include a `password` of at least 12 characters. Use `"role":"ADMIN"` for a second admin.

## Use it on a phone

Phone browsers offer "Add to Home Screen" only for HTTPS sites. For access away from home, use one of these:

- **Tailscale (recommended):** install Tailscale on the Pi and your phone, then run
  `sudo tailscale serve --bg http://localhost:8080`. Open the `https://<machine>.<tailnet>.ts.net` address it prints.
- **Caddy:** point a domain name you control at the Pi and reverse-proxy it to `localhost:8080`. Caddy gets the
  certificate.

Then set `SERVER_SERVLET_SESSION_COOKIE_SECURE=true` in `.env` and run `docker compose up -d`. Sign-in fails over
plain HTTP while this is `true`, so set it only once you use HTTPS.

In the phone browser, open the HTTPS address and use "Add to Home Screen".

## API tokens (phone shortcuts)

A token lets a phone shortcut record an expense without your password on the phone. It works for
`POST /api/v1/expenses` only: any other path with a token gets `403`, and a bad or revoked token gets `401`.
Expenses are attributed to the token's owner. Issue a token with your password (a token cannot issue tokens):

```bash
curl -u sam -H 'Content-Type: application/json' -d '{"name":"Sam iPhone"}' \
  https://centry.example/api/v1/tokens
# {"id":1,"name":"Sam iPhone","token":"centry_..."}   <- shown once, copy it now
```

iOS Shortcuts, action "Get contents of URL":

1. URL: `https://centry.example/api/v1/expenses`, Method: `POST`.
2. Headers: `Authorization` = `Bearer centry_...`, `Content-Type` = `application/json`.
3. Request Body: `JSON`, with `merchant` (Text), `amount` (Number) and `category` (Text).
4. Add "Ask for Input" actions before it and use their results as the field values.

Tasker's HTTP Request action takes the same URL, header and body. List your tokens with
`GET /api/v1/tokens`. Revoke a lost one with `curl -u sam -X DELETE https://centry.example/api/v1/tokens/1`.

## Notifications

Both channels are optional. Set the variables in `.env`, then run `docker compose up -d`.

- **ntfy:** `CENTRY_NOTIFY_NTFY_URL` (for example `https://ntfy.sh`), `CENTRY_NOTIFY_NTFY_TOPIC`, and optionally
  `CENTRY_NOTIFY_NTFY_TOKEN` for a protected topic. Anyone who knows a public ntfy topic name can read it, so
  choose a long random name.
- **Webhook:** `CENTRY_NOTIFY_WEBHOOK_URL`. The payload works with both Discord and Slack.

## Upgrade and rollback

Run upgrades from the install directory:

```bash
cd /opt/centry
scripts/upgrade.sh sha-<new 40 hex characters>
```

The script:

1. Pulls the new image first. If the pull fails, nothing has changed.
2. Stops Centry and copies the database to `/opt/centry/data/backups/centry-<UTC time>-<old tag>.db`. It keeps the
   newest 14 backups.
3. Writes the new tag to `.env` and starts Centry.
4. Waits up to `CENTRY_HEALTH_TIMEOUT` seconds (default 120) for `/actuator/health` to report `UP`.
5. If the health check times out, restores the backup, restores the old tag, starts the old version again, and
   exits with an error.

To roll back by hand, stop Centry, restore a backup, and start the old tag:

```bash
cd /opt/centry
docker compose stop app
sudo cp data/backups/centry-<UTC time>-<old tag>.db data/centry.db
sudo rm -f data/centry.db-wal data/centry.db-shm
sudo chown 10001:10001 data/centry.db
# Set CENTRY_TAG in .env to the old tag, then:
docker compose up -d
```

## Optional: Kafka

Kafka is off by default. Add the override file to run a single-node Kafka broker next to Centry:

```bash
sudo mkdir -p /opt/centry/data/kafka && sudo chown -R 1000:1000 /opt/centry/data/kafka
docker compose -f docker-compose.yml -f docker-compose.kafka.yml up -d
```

The broker runs as uid 1000, so its data directory needs that owner. Upgrades must include the override too:
`COMPOSE_FILES="-f docker-compose.yml -f docker-compose.kafka.yml" scripts/upgrade.sh sha-...`.

With the override, Centry consumes JSON expenses from the `raw-expenses` topic and publishes budget alerts to
`budget-alerts`. Malformed messages go to `raw-expenses.DLT`. The broker's heap is capped at 256 MB, but budget
roughly 300 MB or more of RAM for Kafka on top of Centry. The broker is reachable only on `127.0.0.1:9092`, and it
has no authentication. It advertises itself as `kafka:9092`, so a producer on the Pi itself needs a
`127.0.0.1 kafka` line in `/etc/hosts`.

## API reference

All `/api/**` calls use HTTP Basic authentication with a username and password. Anonymous calls get `401`.
A per-user API token (`Authorization: Bearer ...`) works for `POST /api/v1/expenses` only; see
[API tokens](#api-tokens-phone-shortcuts).

| Method and path | Who | Purpose |
|---|---|---|
| `POST /api/v1/expenses` | admin, member | Record an expense. Returns `201` with a `Location`, or `200` for a replay. |
| `GET /api/v1/expenses/{id}` | admin, member | Read one expense. |
| `GET /api/v1/budgets` | admin, member | List budgets with the current month's spend. |
| `PUT /api/v1/budgets/{category}` | admin | Create or change a budget. |
| `DELETE /api/v1/budgets/{category}` | admin | Remove a budget. |
| `GET /api/v1/users` | admin | List members. |
| `POST /api/v1/users` | admin | Add a member. |
| `POST /api/v1/tokens` | admin, member (Basic only) | Issue an API token. The token is shown once. |
| `GET /api/v1/tokens` | admin, member (Basic only) | List your own tokens (no secrets). |
| `DELETE /api/v1/tokens/{id}` | admin, member (Basic only) | Revoke one of your tokens. `404` for anyone else's. |

Record an expense. `spentBy` is a member id from `GET /api/v1/users`. It is optional and defaults to the caller.
`transactionId` is optional. Send the same value again to avoid a duplicate, and you get the stored expense back.

```bash
curl -u sam -H 'Content-Type: application/json' \
  -d '{"merchant":"Corner Market","amount":12.50,"category":"Groceries","spentBy":2}' \
  http://localhost:8080/api/v1/expenses
```

Set a budget of $600 with a warning at 80%. Category names match ignoring case and surrounding spaces:

```bash
curl -u admin -X PUT -H 'Content-Type: application/json' \
  -d '{"monthlyLimit":600,"warnPercent":80}' \
  http://localhost:8080/api/v1/budgets/Groceries
```

Expenses are edited and deleted in the web UI, not through the API.

## Development

You need JDK 21.

```bash
./gradlew check                            # tests, with 100% line and branch coverage
CENTRY_ADMIN_PASSWORD='a-long-test-pass' ./gradlew bootRun
```

`bootRun` stores its database in `./data/centry.db`. Run the end-to-end smoke test with Docker and curl:

```bash
scripts/smoke.sh
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for the workflow.

## Links

- [CONTRIBUTING.md](CONTRIBUTING.md): how to change the code
- [SECURITY.md](SECURITY.md): how to report a vulnerability, and the deployment security model
- [LICENSE](LICENSE): MIT
- [docs/ROADMAP.md](docs/ROADMAP.md): plans and history
