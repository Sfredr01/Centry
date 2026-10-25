Centry

Smart Expense & Subscription Guardian — lightweight Spring Boot + Kafka backend

## Description

Centry is a small Spring Boot service that demonstrates an event-driven infrastructure for capturing, publishing, and consuming expense-related events. The project pairs a REST API for recording expenses with Kafka-based producers and consumers so you can observe and react to financial events in real time. The codebase is intentionally minimal so it can be hosted on constrained hardware (for example, a Raspberry Pi) or used as a local development sandbox.

Key features

- REST endpoint to submit expense events
- Kafka producer that publishes each expense as an event
- Kafka consumer that demonstrates processing of expense events
- Simple, configurable Spring Boot setup and sensible defaults for local development

## Getting started

Prerequisites

- Java 21 or newer (bytecode targets Java 21, see `build.gradle`)
- Gradle wrapper (included) — `./gradlew`
- Docker and Docker Compose (recommended for running Kafka locally)

Running with Docker Compose (recommended)

1. Start Kafka (KRaft single-node) via Docker Compose:

```bash
docker-compose up -d
```

The included `docker-compose.yml` uses the `confluentinc/cp-kafka` image configured for single-node KRaft mode (no Zookeeper required).

2. Build and run the application:

```bash
./gradlew bootRun
```

3. Submit a test expense (example):

```bash
curl -u admin -X POST http://localhost:8080/api/v1/expenses \
	-H "Content-Type: application/json" \
	-d '{"merchant":"Chipotle","amount":14.50,"category":"Dining"}'
```

## First run / login

Every endpoint except `GET /actuator/health` needs a login. On the first start with an empty database the app
creates an admin from `CENTRY_ADMIN_PASSWORD` (at least 12 characters; user name from `CENTRY_ADMIN_USERNAME`,
default `admin`). With no users and no password it refuses to start. Once any user exists the variable is
ignored and never overwrites anything.

```bash
CENTRY_ADMIN_PASSWORD='a long passphrase' ./gradlew bootRun
# Docker: copy .env.example to .env and set CENTRY_ADMIN_PASSWORD (docker-compose.prod.yml requires it)
```

- **Browsers** sign in on `/login` (form login, optional "remember me" for 30 days; tokens are stored in the
  database and survive restarts). Anonymous page requests redirect to `/login`.
- **Scripts and phone shortcuts** use HTTP Basic on `/api/**`: `curl -u admin:... http://localhost:8080/api/v1/budgets`.
  Unauthenticated API calls get a plain `401` with no `WWW-Authenticate` header, so browsers never pop up a
  Basic prompt or cache credentials.
- **Roles:** `ADMIN` can do everything. `MEMBER` can record expenses and read budgets and the dashboard page, but
  cannot change budgets (API or the Budgets page) or manage users.
- **Users** (admin only): `POST /api/v1/users` with `{"username","displayName","password"?,"role"}` and
  `GET /api/v1/users`. Leave out `password` for a household member who cannot log in (for example a child).
  Passwords are at least 12 characters. Changing passwords and deleting users are not available yet.
- **HTTPS:** the session cookie is `HttpOnly` and `SameSite=Lax`. Plain HTTP is common on a LAN, so `Secure` is off
  by default. If you put the app behind HTTPS, set `SERVER_SERVLET_SESSION_COOKIE_SECURE=true`. Tailscale or a Caddy
  reverse proxy is the easiest way to get HTTPS at home.
- Only `/actuator/health` is exposed, and it shows just the status (the Docker healthcheck uses it).

## Web UI

A phone-first UI is served by the same app (server-rendered Thymeleaf, [htmx](https://htmx.org) and
[Pico.css](https://picocss.com), both vendored under `src/main/resources/static/vendor/`; nothing is loaded from a
CDN and there is no Node toolchain). Sign in on `/login`, then:

- `/add` (the installed app's start page): amount, category, optional merchant and date. Saving twice (a double
  tap) stores one expense. Without JavaScript the form still works.
- `/`: this month's budget bars and the 20 most recent expenses.
- `/budgets`: budgets with their status. Admins can add, edit and delete them; members see a read-only table.

Add it to the home screen to get the standalone app (`/manifest.webmanifest`). There is no service worker, so it
needs a connection. The old `/api/v1/dashboard/*` JSON endpoints are gone; use `GET /api/v1/budgets` for budget
status.

## Budgets

Budgets are stored in the database and managed over the API, not in YAML. Category names are matched
case-insensitively after trimming (`Groceries`, ` groceries ` and `GROCERIES` are one budget).

```bash
# Create or replace a budget; warnPercent is optional (default 80, allowed 1-99)
curl -u admin -X PUT http://localhost:8080/api/v1/budgets/groceries \
	-H "Content-Type: application/json" \
	-d '{"monthlyLimit":600.00,"warnPercent":80}'

# All budgets with this month's spend and level (OK, WARNING_EXCEEDED, LIMIT_EXCEEDED)
curl -u admin http://localhost:8080/api/v1/budgets

# Remove a budget
curl -u admin -X DELETE http://localhost:8080/api/v1/budgets/groceries
```

Spend is summed from the stored expenses for the calendar month of each expense, so it survives restarts. A
category without a budget never alerts. Each category raises at most one warning and one limit alert per month;
the warning amount is `monthlyLimit * warnPercent / 100`, rounded up to a whole cent.

## Notifications

Budget alerts are pushed to every channel that is configured; with none configured they are only logged.

| Property | Meaning |
| --- | --- |
| `centry.notify.ntfy.url`, `centry.notify.ntfy.topic` | Publish to `{url}/{topic}` on an [ntfy](https://ntfy.sh) server (both required) |
| `centry.notify.ntfy.token` | Optional; sent as `Authorization: Bearer <token>` |
| `centry.notify.webhook.url` | Discord/Slack-style webhook (`{"content": ..., "text": ...}`) |
| `centry.notify.retry-interval` | Delay between retry sweeps, default `PT5M` |
| `centry.notify.connect-timeout` / `centry.notify.read-timeout` | Default `PT5S` / `PT10S` |

Each alert row records `notified_at` once every configured channel accepted it. A failed delivery leaves it
empty, and a sweeper retries those alerts every `retry-interval`: it waits one minute after the alert was
raised and gives up on alerts older than 24 hours. Delivery is tracked per alert, so when one channel fails
the retry re-sends to all of them and another channel may get a duplicate. Subscription alerts are not
notified yet.

The old `centry.budget.webhook-url` property is now `centry.notify.webhook.url` (no alias).

## KRaft (Confluent) usage & troubleshooting

- Check Kafka container logs while it starts:

```bash
docker-compose logs -f kafka
```

- Check broker readiness (healthcheck or API versions):

```bash
docker exec -it kafka bash -c "kafka-broker-api-versions --bootstrap-server localhost:9092"
```

- If the broker isn't reachable from other hosts, adjust `KAFKA_ADVERTISED_LISTENERS` in `docker-compose.yml` to an accessible host/IP and restart the compose stack.
- To stop and remove the stack:

```bash
docker-compose down
```

- Common issues:
  - Memory or architecture: the Confluent images are x86_64; use an ARM-compatible image on Raspberry Pi or run on an x86 host.
  - Port conflicts: ensure `9092` is free on the host or map to a different external port.
  - Topic creation/permissions: set `KAFKA_AUTO_CREATE_TOPICS_ENABLE` to `true` (already enabled) or pre-create topics using `kafka-topics`.

## Tips

- Use `kcat` or `kafka-console-consumer.sh` to view topic messages for quick verification.
- For CI, prefer an embedded Kafka test container (e.g., Testcontainers) instead of Docker Compose.

Running locally without Docker

1. Make sure a Kafka broker is reachable (set bootstrap servers in `application.yml` or via environment variable)
2. Build and run the app:

```bash
./gradlew build
./gradlew bootRun
```

## Configuration

All Spring and Kafka configuration is in `src/main/resources/application.yml`. Override settings with `--spring.profiles.active=dev` or environment variables.

Useful Gradle tasks

- `./gradlew bootRun` — Run the application
- `./gradlew build` — Build the JAR
- `./gradlew test` — Run tests

Project structure (high level)

- `src/main/java/com/centry/controller` — REST controllers (expense endpoint)
- `src/main/java/com/centry/service` — Kafka producer/consumer and application logic
- `src/main/resources` — application.yml, templates, static assets

## Contributing

1. Fork the repo and create a feature branch
2. Add tests for new behavior
3. Open a pull request with a clear description

## License

Add a license file to the project root (for example, MIT) if you intend to open source this project.

## Next steps

- Add examples for testing with an embedded Kafka broker for CI
- Add health checks and metrics

## Deploying to Raspberry Pi

This project runs on Raspberry Pi-class hardware with a compatible Java runtime. There are two deployment approaches: run the service directly on the Pi (recommended for small setups) or run the service in containers and use an ARM-friendly Kafka distribution.

1. Native JVM deployment (simple)

- Build the runnable JAR on your development machine (or build on the Pi):

```bash
./gradlew clean bootJar
```

- Copy the JAR to the Pi:

```bash
scp build/libs/*.jar pi@raspberrypi:/home/pi/centry/centry.jar
```

- Install a JDK on the Pi (Debian/Raspbian example):

```bash
sudo apt update
sudo apt install -y openjdk-21-jdk
```

- Run manually for testing:

```bash
java -jar /home/pi/centry/centry.jar --spring.profiles.active=prod
```

- Create a `systemd` service to run at boot (`/etc/systemd/system/centry.service`):

```ini
[Unit]
Description=Centry Spring Boot Service
After=network.target

[Service]
User=pi
WorkingDirectory=/home/pi/centry
ExecStart=/usr/bin/java -jar /home/pi/centry/centry.jar --spring.profiles.active=prod
SuccessExitStatus=143
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
```

Enable and start the service:

```bash
sudo systemctl daemon-reload
sudo systemctl enable centry
sudo systemctl start centry
```

2. Containerized deployment on Raspberry Pi (ARM)

- Confluent `cp-kafka` images are x86-only and won't run on ARM. Options for an ARM-compatible broker:
  - Use Redpanda (Kafka API compatible) — multi-arch and lightweight; recommended for Pi.
  - Use Bitnami Kafka images (may support ARM depending on tag) with Zookeeper.

Redpanda single-node example (recommended for Raspberry Pi):

```yaml
version: '3.8'
services:
	redpanda:
		image: vectorized/redpanda:latest
		command: ["redpanda", "start", "--overprovisioned", "--smp 1", "--memory 512M", "--reserve-memory 0M", "--node-id 0"]
		ports:
			- "9092:9092"
		volumes:
			- ./redpanda-data:/var/lib/redpanda/data
```

Start the broker on the Pi and then run the service (native or container). Point the app's Kafka bootstrap servers to the Pi's broker address.

Notes & tips

- Verify your Pi architecture (`uname -m`) and install a matching JDK (aarch64 vs armv7).
- If you cannot run the broker on the Pi, run Kafka/Redpanda on another host and point the app at that address.
- For production on Pi-class hosts, monitor memory and disk usage and limit JVM heap size via `-Xmx`.
