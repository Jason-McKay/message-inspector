---
name: run-stack
description: Launch the full Message Inspector stack (shared Docker infra, Spring Boot backend, Angular frontend) and verify it end-to-end. Use when asked to run/start the app, demo a change in the running system, or when a task needs a live broker.
---

# Run the Message Inspector stack

Three pieces, started in this order. CLAUDE.md carries the deeper gotchas; this is the
launch procedure.

## 1. Infrastructure (Docker)

From `../infrastructure` — **not** this repo's bare `compose.yaml` (no fixed port
mapping, Artemis only):

```bash
cd ../infrastructure && docker compose up -d && ./test-containers.sh
```

Brings up Artemis 2.31.2 (:61616 JMS, :8161 console/Jolokia), Redis (:6379), MySQL
(unused here). All creds `admin`/`admin`. Wait for `test-containers.sh` to pass before
starting the backend — `SecurityConfigTest` and the live Jolokia path fail confusingly
if infra is down.

## 2. Backend (:8080)

```bash
./mvnw spring-boot:run        # .\mvnw.cmd in PowerShell
```

Run in the background for long sessions. Verify: `http://localhost:8080/api/swagger-ui.html`
(dev profile only — default profile is dev; any other `SPRING_PROFILES_ACTIVE` serves 404).

**Teardown gotcha:** the forked JVM outlives the Maven wrapper. Kill the `java` process
holding :8080, not the wrapper task, or the next run fails with "Port 8080 was already
in use".

## 3. Frontend (:4200) — separate repo

```bash
cd ../message-inspector-ui
nvm use 24.18.0               # .nvmrc pin for Angular 22; also the global default
npm start
```

Headless tests there: `npx ng test --watch=false --browsers=ChromeHeadless`; lint with
`npm run lint` (CI runs both).

## Seeding test messages

```bash
MSYS_NO_PATHCONV=1 docker exec dev-artemis /opt/activemq-artemis/bin/artemis producer \
  --url tcp://localhost:61616 --destination queue://DLQ --message-count 3 \
  --user admin --password admin
```

Clean up afterwards: purge the queue and delete any `DLQ_test` / `ActiveMQQueue[DLQ]`
addresses left behind. Remember browsing a nonexistent queue auto-creates its address —
don't probe with typos.

## Quick end-to-end check

1. Infra healthy (`test-containers.sh` green).
2. Backend up: Swagger loads, `GET /api/queues/artemis-jolokia/names` returns a JSON
   array (the queue-discovery route; the two dead `[]` routes were deleted 2026-07-24).
3. Frontend up: queue dropdown populates at `http://localhost:4200`.
