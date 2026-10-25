# Centry Raspberry Pi Provisioning & Setup Guide

This guide provides step-by-step instructions for deploying the **Centry** personal finance stream-processing service onto a Raspberry Pi running **Raspberry Pi OS Lite (64-bit / ARM64, Debian Bookworm)** as a production-grade, systemd-managed headless service.

---

## 1. System Prerequisites & OS Provisioning

### OS & Headless Setup
1. Flash **Raspberry Pi OS Lite (64-bit)** (Debian Bookworm) onto your SD card using Raspberry Pi Imager.
2. In the imager advanced settings (gear icon):
   - Set hostname to `centry-pi`.
   - Enable SSH (using public-key authentication).
   - Configure Wi-Fi / Ethernet settings.
3. Power on your Raspberry Pi and SSH into it:
   ```bash
   ssh pi@centry-pi.local
   ```

### Memory Split & Swap Configuration
Raspberry Pi OS allocates significant RAM to the GPU by default. For a headless server running Java and Kafka, reclaim this memory.

1. **GPU Memory Split**:
   Open raspi-config:
   ```bash
   sudo raspi-config
   ```
   Navigate to **Performance Options** -> **GPU Memory** and set it to `16` MB. (Or edit `/boot/firmware/config.txt` directly to ensure `gpu_mem=16`).

2. **Configure 2GB+ Swap File**:
   Kafka and JVM compilation/startup can spike memory. Configuring adequate swap prevents Out-Of-Memory (OOM) kills.
   ```bash
   sudo dphys-swapfile swapoff
   sudo nano /etc/dphys-swapfile
   ```
   Set `CONF_SWAPSIZE=2048` (2GB). Save and exit.
   ```bash
   sudo dphys-swapfile setup
   sudo dphys-swapfile swapon
   sudo systemctl restart dphys-swapfile
   ```

---

## 2. Installing Docker & Docker Compose

Install the official Docker Engine and Compose plugin for Debian ARM64:

```bash
# Update package index and install dependencies
sudo apt-get update
sudo apt-get install -y ca-certificates curl gnupg

# Add Docker's official GPG key
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/debian/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg

# Set up the stable Docker repository
echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/debian \
  $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | \
  sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

# Install Docker Engine & Compose plugin
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

# Add current user to docker group (so sudo isn't required)
sudo usermod -aG docker $USER
newgrp docker

# Enable and start docker service
sudo systemctl enable --now docker
```

---

## 3. Project Deployment Architecture

Create a standard production deployment directory structure at `/opt/centry`:

```bash
sudo mkdir -p /opt/centry/data/kafka /opt/centry/data/app
sudo chown -R $USER:$USER /opt/centry
cd /opt/centry
```

### Production Docker Compose (`docker-compose.prod.yml`)
Create `docker-compose.prod.yml` configured specifically for Raspberry Pi resource constraints:

```yaml
version: '3.8'

services:
  kafka:
    image: confluentinc/cp-kafka:7.4.1
    container_name: centry-kafka
    restart: unless-stopped
    ports:
      - "9092:9092"
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: 'broker,controller'
      KAFKA_LISTENERS: 'PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093'
      KAFKA_ADVERTISED_LISTENERS: 'PLAINTEXT://localhost:9092'
      KAFKA_CONTROLLER_LISTENER_NAMES: 'CONTROLLER'
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: 'CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT'
      KAFKA_CONTROLLER_QUORUM_VOTERS: '1@kafka:9093'
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
      KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS: 0
      KAFKA_METADATA_LOG_SEGMENT_BYTES: 104857600
      KAFKA_JVM_PERFORMANCE_OPTS: "-Xmx384m -Xms256m"
      CLUSTER_ID: 'Mk3OEYBSD3T240AB8d8E8W'
    volumes:
      - /opt/centry/data/kafka:/var/lib/kafka/data
    deploy:
      resources:
        limits:
          memory: 512M
    healthcheck:
      test: ["CMD", "nc", "-z", "localhost", "9092"]
      interval: 10s
      timeout: 5s
      retries: 5

  centry-app:
    image: centry:latest
    container_name: centry-app
    restart: unless-stopped
    depends_on:
      kafka:
        condition: service_healthy
    ports:
      - "8080:8080"
    environment:
      SPRING_PROFILES_ACTIVE: prod
      SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:9092
      JAVA_TOOL_OPTIONS: "-Xmx384m -Xms128m -XX:+UseG1GC"
    volumes:
      - /opt/centry/data/app:/data
    deploy:
      resources:
        limits:
        memory: 512M
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/actuator/health"]
      interval: 15s
      timeout: 5s
      retries: 5
```

### Production Environment Config (`src/main/resources/application-prod.yml`)
Ensure your application has a `prod` profile configured for production pathing (e.g., SQLite/H2 file database stored in `/data/centrydb`):

```yaml
server:
  port: 8080

spring:
  application:
    name: centry
  datasource:
    url: jdbc:h2:file:/data/centrydb;AUTO_SERVER=TRUE
    driver-class-name: org.h2.Driver
    username: sa
    password: 
  jpa:
    database-platform: org.hibernate.dialect.H2Dialect
    hibernate:
      ddl-auto: update
    show-sql: false
  kafka:
    bootstrap-servers: kafka:9092
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
    consumer:
      group-id: centry-group
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: "com.centry.*"

centry:
  budget:
    webhook-url: "http://localhost:4500/notification"
    categories:
      dining:
        monthly-limit: 300.00
        warning-threshold-percent: 0.80
      groceries:
        monthly-limit: 500.00
        warning-threshold-percent: 0.85
      utilities:
        monthly-limit: 250.00
        warning-threshold-percent: 0.90

management:
  endpoints:
    web:
      exposure:
        include: "health,info,metrics,prometheus"
  endpoint:
    health:
      show-details: always
```

---

## 4. Multi-Arch / ARM64 Image Build & Transfer Strategy

### Option A: Local Cross-Compilation (Mac M1/M2/M3 to ARM64 Pi)
Build the ARM64 Docker image on your developer workstation using Buildx, save to tar, and transfer:

```bash
# On your local Mac machine:
docker buildx create --use
docker buildx build --platform linux/arm64 -t centry:latest --load .
docker save centry:latest -o centry-arm64.tar

# Transfer to Raspberry Pi via scp:
scp centry-arm64.tar pi@centry-pi.local:/opt/centry/

# On Raspberry Pi:
docker load -i /opt/centry/centry-arm64.tar
```

### Option B: Production Multi-Stage `Dockerfile`
If building directly on the Raspberry Pi or via CI/CD, use this lean multi-stage Eclipse Temurin Java 23 Alpine/Debian Dockerfile:

```dockerfile
# Stage 1: Build the application with Gradle
FROM eclipse-temurin:23-jdk-jammy AS builder
WORKDIR /app
COPY gradlew build.gradle settings.gradle ./
COPY gradle gradle
COPY src src
RUN ./gradlew bootJar -x test

# Stage 2: Runtime image using lightweight JRE
FROM eclipse-temurin:23-jre-jammy
WORKDIR /app
RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*
COPY --from=builder /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

---

## 5. Systemd Service & Auto-Restart on Boot

Create a systemd unit file to manage the Centry Docker Compose stack across system reboots and shutdowns.

1. Create `/etc/systemd/system/centry.service`:
   ```bash
   sudo nano /etc/systemd/system/centry.service
   ```

2. Add the following service definition:
   ```ini
   [Unit]
   Description=Centry Personal Finance Service (Docker Compose)
   Requires=docker.service
   After=docker.service network-online.target

   [Service]
   Type=oneshot
   RemainAfterExit=yes
   WorkingDirectory=/opt/centry
   ExecStart=/usr/bin/docker compose -f docker-compose.prod.yml up -d
   ExecStop=/usr/bin/docker compose -f docker-compose.prod.yml down
   TimeoutStartSec=300

   [Install]
   WantedBy=multi-user.target
   ```

3. Enable and start the systemd service:
   ```bash
   sudo systemctl daemon-reload
   sudo systemctl enable --now centry.service
   ```

---

## 6. Operational Verification & Health Checks

### Check Systemd & Container Status
```bash
sudo systemctl status centry.service
docker compose -f /opt/centry/docker-compose.prod.yml ps
docker compose -f /opt/centry/docker-compose.prod.yml logs -f centry-app
```

### Verify Actuator Health Endpoint
```bash
curl -s http://localhost:8080/actuator/health | jq .
```

### End-to-End Test: Publish a Transaction
Send a test JSON transaction to verify end-to-end ingestion, Kafka event production/consumption, budget tracking, subscription cadence analysis, and DB persistence:

```bash
curl -X POST http://localhost:8080/api/v1/expenses \
  -H "Content-Type: application/json" \
  -d '{
    "transactionId": "tx-pi-001",
    "merchant": "Netflix",
    "amount": 17.99,
    "category": "utilities",
    "timestamp": "2026-10-15T12:00:00"
  }'
```

Verify dashboard summary and stored records:
```bash
curl -s http://localhost:8080/api/v1/dashboard/summary | jq .
curl -s http://localhost:8080/api/v1/dashboard/expenses | jq .
```
