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

- Java 17 (or the JDK version configured in `build.gradle`)
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
curl -X POST http://localhost:8080/api/v1/expenses \
	-H "Content-Type: application/json" \
	-d '{"merchant":"Chipotle","amount":14.50,"category":"Dining"}'
```

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
- Add authentication for the REST API

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
sudo apt install -y openjdk-17-jdk
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
