# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Backend of Message Inspector: a Spring Boot 3.2 / Java 21 tool to browse, resend, delete,
and DLQ-move messages on an ActiveMQ Artemis broker. It is the server half of a two-repo
full-stack app:

- Backend (this repo): `C:\Users\jason\Projects\message-inspector\`
- Frontend: `C:\Users\jason\Projects\message-inspector-ui\` (Angular 16, Node 20.11.1, :4200)
- Shared dev infra: `C:\Users\jason\Projects\infrastructure\` (own git repo, local only)
- Roadmap: `C:\Users\jason\Claude\plans\message-inspector-completion-plan.md` — phased plan with the
  current phase, known gaps, and deliberately deferred work. **Read it before starting
  non-trivial work**; it explains why several rough edges below are still rough.

## Commands

Maven wrapper (`./mvnw` in Bash, `.\mvnw.cmd` in PowerShell):

```bash
./mvnw clean compile
./mvnw test                                  # unit tests only (surefire)
./mvnw verify                                # unit + *IT integration tests (failsafe)
./mvnw spring-boot:run                       # boots on :8080
./mvnw test -Dtest=JolokiaRepositoryTest     # single test class
./mvnw test -Dtest=JolokiaRepositoryTest#methodName
```

For the **dev loop** (edit + `spring-boot:run` / `mvnw`), bring the shared infra up first
from `../infrastructure` — it provides the broker + Redis the locally-run app talks to:

```bash
cd ../infrastructure && docker compose up -d && ./test-containers.sh
```

That brings up Artemis 2.31.2 (:61616 JMS, :8161 console/Jolokia), Redis (:6379), and
MySQL (unused by this app). Credentials are `admin`/`admin` throughout local dev.

For a **one-command run of the whole backend** (Phase 7), this repo's `compose.yaml` is now
a self-contained stack — it **builds the app image** (`Dockerfile`) and wires it to its own
Artemis + Redis, reachable by service name (no shared infra, no dev-broker contact):

```bash
docker compose up --build      # app :8080, Artemis console :8161, both admin/admin
```

The app reaches its siblings via env overrides in `compose.yaml`
(`SPRING_ARTEMIS_BROKER_URL`, `JOLOKIA_URL`, `SPRING_DATA_REDIS_HOST`) — Spring relaxed
binding over the localhost defaults in `application.properties`. This works because Redis
host/port is now property-driven (`spring.data.redis.*`); it was hardcoded to
`localhost:6379` in `RedisConfig` until Phase 7, which had also made
`RedisQueueRepositoryIntegrationTest` silently depend on a live localhost Redis.

CI/CD (Phase 7): `.github/workflows/ci.yml` runs `./mvnw test` on push/PR;
`.github/workflows/security.yml` runs the OWASP gate nightly + on demand (needs the
`NVD_API_KEY` repo secret).

`http/message-inspector.http` is a runnable end-to-end scenario for the whole API — logs
in as both `admin` and `jason`, captures the JWTs, and asserts the behaviours the tests
can't reach from outside (401 unauthenticated, 403 on an ADMIN-only delete as USER, the
`SAFE_MESSAGE_ID` 400 on selector injection, and truthful failure on a missing message).
Select the `dev` environment from `http/http-client.env.json` before running; anything
real goes in `http/http-client.private.env.json`, which is gitignored. Works in IntelliJ's
HTTP Client and VS Code's REST Client.

Swagger UI: `http://localhost:8080/api/swagger-ui.html`; OpenAPI JSON at `/api/api-docs`.
**Dev-profile only** (and dev is the default profile): the base config sets
`springdoc.*.enabled=false`, so any run with `SPRING_PROFILES_ACTIVE` set to something
other than `dev` serves 404 on both routes. `SpringdocExposureTest` pins this.

### Testing notes

- Several tests use **Testcontainers and need Docker running**. The surefire `argLine`
  in `pom.xml` pins `-Dapi.version=1.44`; do not remove it. docker-java's default Docker
  API version (1.32) is below the 1.40 minimum enforced by Docker Engine 29+, which fails
  with the misleading "Could not find a valid Docker environment".
- **`./mvnw test` does not run `MessageServiceIT`** — surefire's includes do not match
  `*IT`. That is deliberate: `mvn test` stays fast and unit-only. **Use `./mvnw verify`**
  to run it via failsafe. (Passing an explicit `-Dtest=...` filter to surefire also drags
  it in, which is not what you want — it will run without failsafe's setup.)
- `MessageServiceIT` is **self-contained**: it starts its own Artemis container
  (`apache/activemq-artemis:2.31.2`, matching the dev stack), seeds its own message, and
  drains both queues before each test. It needs Docker but not the shared infra, and it
  does not touch the dev broker. It is the only automated test that opens a JMS
  connection — everything else at the message level is Mockito-based.
- `SecurityConfigTest` exercises the real filter chain (CORS preflight + a 401 on an
  unauthenticated request). It is **broker-independent** — the old
  `testApiIsCurrentlyPermittedWithoutAuthentication`, which hit the live Jolokia path and
  needed the shared infra, was replaced in Phase 4 by `testApiRequiresAuthentication`
  (security rejects before any controller/broker call). The whole `./mvnw test` suite now
  passes with the shared infra down, given Docker is up for the Testcontainers tests.
- **Verify JMS changes by hand.** Boot the app, produce with
  `MSYS_NO_PATHCONV=1 docker exec dev-artemis /opt/activemq-artemis/bin/artemis producer
  --url tcp://localhost:61616 --destination queue://DLQ --message-count 3 --user admin
  --password admin`, then exercise browse / get-by-id / delete / resend / move-to-dlq.
  This pass has caught real bugs the suite could not see. Clean up afterwards: purge the
  DLQ and delete any `DLQ_test` or `ActiveMQQueue[DLQ]` addresses it leaves behind.
- **`spring-boot:run` in the background outlives its Maven wrapper.** Killing the wrapper
  task leaves the forked JVM holding :8080, and the next run fails with "Port 8080 was
  already in use". Kill the `java` process by port, not the wrapper.

## Architecture

Layered packages under `com.message.inspector.message_inspector`. Both controllers are
mapped under `/api`.

```
config/       Application, Artemis, Redis, RestTemplate, Swagger wiring
controller/   MessageController, QueueController
dto/          ApiResponse, JmsMessageDto, JolokiaResponse
integration/  artemis/  JmsMessageRepository                       (JMS)
              jolokia/  JolokiaRepository                          (HTTP/JMX)
              cache/    RedisQueueRepository                       (Redis)
security/     SecurityConfig
service/      MessageService, QueueService, QueuePreferenceService
```

**Everything that talks to an external system lives under `integration/`.** There is no
`repository/` package and no persistence layer — this app owns no data, it reads and
mutates someone else's broker. `cache/` names the role rather than the technology because
what it stores is UI state with a TTL.

There is deliberately **no `domain/` or `mapper/` package yet.** Every type in `dto/` is a
wire format; adding either would assert a layer that does not exist. They arrive with the
first real domain model.

Two independent broker access paths, and the distinction matters:

- **JMS (`JmsTemplate`)** — everything at the *message* level. `JmsMessageRepository`
  browses, selectively consumes, and republishes messages.
- **Jolokia HTTP (`JolokiaRepository` + `jolokiaRestTemplate`)** — everything at the
  *queue* level. Artemis exposes queue/address metadata over JMX, reachable only via the
  console's Jolokia bridge, so queue discovery is an HTTP call to :8161, not JMS.
  `JolokiaRepository` builds MBean URLs by string-formatting `jolokia.brokerName`.

Redis (`RedisQueueRepository`, key prefix `lastUsedQueue:`, 7-day TTL) stores per-user
last-used-queue preference. It is a cache of UI state, not application data.

### Gotchas

- **Only one queue-discovery route exists: `/api/queues/artemis-jolokia/names`.** There
  used to be two more — `/artemis-straight` (JMS) and `/artemis-jolokia` (Jolokia,
  queue-list shape) — both of which always returned `[]` for different swallowed reasons.
  They were **deleted 2026-07-24** along with their backing code (`JmsQueueRepository`,
  `JolokiaRepository.fetchQueuesWithJolokia`, and the `JmsQueueDto`/`JolokiaReadResponse`
  DTOs), because only the names route ever worked and the dropdown already uses it.
- **`delete`, `move-to-dlq` and `resend` all report truthfully on a missing message**
  (fixed Phase 4). `deleteMessageById`/`moveToDeadLetterQueue` return `boolean`, and
  `MessageService` sets `success=false, "Message not found."` for a no-op rather than a
  false success. `MessageServiceIT` covers the missing-message case for all three.
- **The DLQ target is the `message-inspector.dlq-name` property (default `DLQ`)**, read by
  `JmsMessageRepository` — not a hardcoded address. The old `DLQ_NAME = "DLQ_test"` constant
  (which made "Move to DLQ" write to an app-invented address) was replaced in Phase 4 and
  removed entirely with `JmsQueueRepository` on 2026-07-24.
- **Browsing a queue that does not exist auto-creates the address**, so typos permanently
  pollute the queue list on the dev broker.
- ~~**The Jolokia envelope leaks to the client.**~~ **FIXED 2026-07-23**:
  `/artemis-jolokia/names` returns a bare JSON array; the JMX envelope is unwrapped in
  `JolokiaRepository` (where `JolokiaResponse` remains the deserialization target — it
  just never crosses the repository boundary any more). Coordinated with the frontend,
  whose `QueueService` was made tolerant of both shapes first.
- **`ArtemisConfig` builds its own `ConnectionFactory`** rather than using Boot's, which
  `spring.main.allow-bean-definition-overriding=true` is what permits. It now *reads*
  `spring.artemis.broker-url`/`user`/`password` (defaults match the local stack), so
  those properties are live — they used to be hardcoded and therefore inert, which is
  why `MessageServiceIT` could only ever target `localhost:61616` and now can point at a
  container. Jolokia credentials (`JOLOKIA_USER`/`JOLOKIA_PASSWORD`) are wired via
  `RestTemplateConfig`.
- **JMS selectors are built by string concatenation.** `JmsMessageRepository` guards every
  such path with `requireValidMessageId` against `SAFE_MESSAGE_ID`. Any new selector-taking
  method must call it too — without it, an ID like `x' OR '1'='1` widens the selector and
  the wrong message gets consumed or DLQ'd.
- **`SecurityConfig` permits `/api/**` unauthenticated**, including the destructive
  endpoints, which makes the trailing `anyRequest().authenticated()` unreachable. This is
  deliberate until the auth phase; the file carries a tripwire comment noting that CSRF
  must be re-enabled if auth ever moves to cookies (CORS already sets
  `allowCredentials(true)` for `http://localhost:4200`).
- `jolokiaRestTemplate` registers a Jackson converter for `text/plain` because Jolokia
  returns JSON under that content type.
- **Destination names must come from `getQueueName()`, not `toString()`.** On Artemis,
  `destination.toString()` yields `ActiveMQQueue[DLQ]` rather than `DLQ`. `convertToDto`
  used to do exactly that, which put the wrong name in front of the user *and* broke
  resend, because `resendMessage` fed the value back into `sendMessage` and delivered to
  an address literally named `ActiveMQQueue[DLQ]` while reporting success. Fixed
  2026-07-21 via `destinationName(...)`; `resendMessage` also now uses the caller's queue
  name rather than the one round-tripped through the DTO. `MessageServiceIT` pins both.
  Note the mock-based tests could never catch this — they supply a clean queue name the
  real code path did not produce.
- **Error responses** (fixed 2026-07-21; `/error` used to be unpermitted, which turned
  *every* server error into a misleading 403). Current mapping, all verified against a
  live broker:
  - `IllegalArgumentException` (the `SAFE_MESSAGE_ID` guard) → **400**, with a generic
    message; the offending input is deliberately not echoed back.
  - `jakarta.jms.JMSException` → **502**, on the grounds that the broker is an upstream
    dependency. A browse against a stopped broker returns
    `{"success":false,"message":"Broker unavailable: ..."}`.
  - `JolokiaAccessException` → **502**, same upstream-dependency reasoning:
    `fetchQueueNamesWithJolokia` wraps its failures in this type (fixed 2026-07-23;
    they used to be bare `RuntimeException`s that fell through to 500).
  - Anything else → **500** via `/error`.
  - `ApiExceptionHandler` reuses `ApiResponse` so failures match the shape the
    destructive endpoints already return, rather than introducing a second error format.

## Conventions

- **2-space indentation** (not 4) across the Java sources.
- Lombok `@RequiredArgsConstructor` with `private final` fields for injection; `@Data` on
  DTOs. No `@Autowired` on constructors.
- Logging is `System.out.println` throughout rather than SLF4J. Match the surrounding file
  rather than introducing a logger mid-change.
- Service methods that wrap destructive operations catch `JMSException` and return
  `ApiResponse{success=false}` rather than propagating — tests asserting a thrown exception
  from those paths are wrong.
- Sample Jolokia payloads for reference live in `src/main/resources/info/`.
- Conventional commits (`feat:`, `fix:`, `refactor:`, `test:`, `chore:`, `docs:`).
