# Centry

Centry is a family expense tracker and budget guard. It runs as one Spring Boot container on a Raspberry Pi
at home, and you use it from a phone or laptop.

- Quick-add form, installable as a home-screen app (PWA), for logging a purchase in a few taps.
- Monthly budgets per category, with a bar on the dashboard.
- One-time WARN (at the warning percent, default 80%) and EXCEEDED (at 100%) alerts per category and month, sent
  to ntfy or a Discord/Slack-style webhook. Failed deliveries are retried for up to 24 hours.
- Who spent what: each expense is attributed to a household member, and the dashboard filters by member.
- Roles: an admin manages members, budgets and subscriptions. A member adds expenses and edits or deletes only the ones they
  created. Members without a password can be picked as the spender but cannot log in.
- Subscriptions: the Subs tab lists recurring charges (monthly or yearly) with their expected amount, next due
  date and a yearly total. For now an admin adds and edits them by hand; everyone signed in can read the list.
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

## Notifications

Both channels are optional. Set the variables in `.env`, then run `docker compose up -d`.

- **ntfy:** `CENTRY_NOTIFY_NTFY_URL` (for example `https://ntfy.sh`), `CENTRY_NOTIFY_NTFY_TOPIC`, and optionally
  `CENTRY_NOTIFY_NTFY_TOKEN` for a protected topic. Anyone who knows a public ntfy topic name can read it, so
  choose a long random name.
- **Webhook:** `CENTRY_NOTIFY_WEBHOOK_URL`. The payload works with both Discord and Slack.

## Export

Admins see an **Export** card at the bottom of the Budgets page. Pick two days (both included, at most five years
apart; the defaults are the first and last day of this month) and download a CSV with the columns `date, merchant,
category, amount, spent_by, created_by, source, transaction_id`. Amounts are plain decimals such as `12.50`.
`source` is `app` for expenses a person entered and `feed` for the rest (Kafka and older rows). Text that a
spreadsheet could read as a formula gets a leading `'`, so a merchant called `=1+1` stays text in Excel.

## Backups

Centry copies its database every night at 03:30 household time (`TZ`) to `data/backups/nightly-YYYYMMDD.db` and
keeps the newest 14. These are separate from the `centry-*.db` files that `scripts/upgrade.sh` writes. Set
`CENTRY_BACKUP_CRON`, `CENTRY_BACKUP_KEEP` or `CENTRY_BACKUP_ENABLED=false` in `.env` to change that.

To go back to a backup, run this from the install directory (`ls data/backups` lists the names):

```bash
cd /opt/centry
scripts/restore.sh nightly-20260315.db
```

It stops Centry, moves the current database to `data/backups/pre-restore-<UTC time>.db`, copies the backup into
place, starts Centry and waits for it to be healthy. If you restored the wrong day, run `scripts/restore.sh` with
that `pre-restore-...` name to put things back. Use `COMPOSE_FILES` as for upgrades if you run the Kafka override.
### Monthly digest

On the 1st of each month at 08:00 household time (`TZ`), one low-priority notification summarises the month before:
the total spent, each budgeted category against its limit (`Groceries $512.40 / $600 (85%)`), spend with no
budget, the top three merchants, and spend per household member (expenses without a member count as
"Unassigned"). A month without expenses still sends "No expenses recorded in <Month>", so you can see the job
runs.

- Each month is sent once, even across restarts. If a channel fails, the digest is retried every few minutes for
  24 hours, like budget alerts.
- If the app was down at 08:00, the digest goes out within the hour after it is back up, up to three days later.
- `CENTRY_DIGEST_CRON` changes the schedule (Spring cron with seconds, default `0 0 8 1 * *`).
  `CENTRY_DIGEST_ENABLED=false` turns the digest off.

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

| Method and path | Who | Purpose |
|---|---|---|
| `POST /api/v1/expenses` | admin, member | Record an expense. Returns `201` with a `Location`, or `200` for a replay. |
| `GET /api/v1/expenses/{id}` | admin, member | Read one expense. |
| `GET /api/v1/budgets` | admin, member | List budgets with the current month's spend. |
| `PUT /api/v1/budgets/{category}` | admin | Create or change a budget. |
| `DELETE /api/v1/budgets/{category}` | admin | Remove a budget. |
| `GET /api/v1/users` | admin | List members. |
| `POST /api/v1/users` | admin | Add a member. |
| `GET /export/expenses.csv?from=YYYY-MM-DD&to=YYYY-MM-DD` | admin | Download expenses as CSV (browser session; both dates required and inclusive). |

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
scripts/test-upgrade.sh    # upgrade, rollback and prune
scripts/test-restore.sh    # restore from a backup
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for the workflow.

## Links

- [CONTRIBUTING.md](CONTRIBUTING.md): how to change the code
- [SECURITY.md](SECURITY.md): how to report a vulnerability, and the deployment security model
- [LICENSE](LICENSE): MIT
- [docs/ROADMAP.md](docs/ROADMAP.md): plans and history
