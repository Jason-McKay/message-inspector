# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Backend of Message Inspector: a Spring Boot 3.2 / Java 21 tool to browse, resend, delete,
and DLQ-move messages on an ActiveMQ Artemis broker. It is the server half of a two-repo
full-stack app:

- Backend (this repo): `C:\Users\jason\Claude\message-inspector\`
- Frontend: `C:\Users\jason\Claude\message-inspector-ui\` (Angular 16, Node 20.11.1, :4200)
- Shared dev infra: `C:\Users\jason\Claude\infrastructure\` (not a git repo)
- Roadmap: `C:\Users\jason\Claude\message-inspector-plan.md` — phased plan with the
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

Infrastructure must be up first — start it from `../infrastructure`, **not** from this
repo's bare `compose.yaml` (which only defines Artemis with no fixed port mapping):

```bash
cd ../infrastructure && docker compose up -d && ./test-containers.sh
```

That brings up Artemis 2.31.2 (:61616 JMS, :8161 console/Jolokia), Redis (:6379), and
MySQL (unused by this app). Credentials are `admin`/`admin` throughout local dev.

Swagger UI: `http://localhost:8080/api/swagger-ui.html`; OpenAPI JSON at `/api/api-docs`.

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
- `SecurityConfigTest` exercises the real filter chain *and* the live Jolokia path, so
  **it fails if the shared infra is down**, which reads as a test failure rather than a
  missing container. Bring `../infrastructure` up before trusting a red run.
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
dto/          ApiResponse, JmsMessageDto, JmsQueueDto, JolokiaReadResponse, JolokiaResponse
integration/  artemis/  JmsMessageRepository, JmsQueueRepository   (JMS)
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

- **`/api/queues/artemis-straight` always returns `[]`.** Earlier notes here claimed it
  returns hardcoded placeholders (`test.queue.1..3`); that is wrong. The method calls
  `session.createBrowser(null)`, which throws `Cannot create a browser with a null queue`
  *before* reaching the placeholder loop, and the broad catch swallows it into an empty
  list. Verified against a live broker 2026-07-21. The placeholder code is unreachable.
  Note the mock-based tests cannot see
  this — they stub the layer that fails.
- **`/api/queues/artemis-jolokia` always returns `[]` too.** *Two* of the three discovery
  endpoints are dead; only `/api/queues/artemis-jolokia/names` works. (An earlier note here
  said Jolokia was "the only working queue-discovery path" — too generous: it is the only
  working *route*, not the only working Jolokia method.) Two stacked causes, verified live
  2026-07-21:
  1. `JolokiaRepository.fetchQueuesWithJolokia` builds `HttpHeaders` with
     `Accept: application/json`, then passes `null` as the request entity — the header
     never goes out. Jolokia answers `text/plain`, no converter binds that to
     `JolokiaReadResponse`, and the broad `catch (Exception)` turns the failure into an
     empty list and an HTTP 200.
  2. Even with the header sent, `JolokiaReadResponse.value` is a
     `Map<String, QueueDetails>` while the requested `/AddressNames` returns a **JSON array
     of strings** — `fetchQueueNamesWithJolokia` hits the same URL and binds it correctly
     as `List<String>`. The DTO wants a different MBean query.

  `JolokiaRepositoryTest` cannot catch either: it stubs the RestTemplate with a populated
  `Map`, a shape the live endpoint never returns, and asserts the URL that is wrong for
  that DTO. The characterisation tests pin the mock, not the endpoint. `QueueControllerTest`
  compounds it by describing this route as "the working queue-discovery route".
- **`delete` and `move-to-dlq` report success for a message that does not exist.**
  `deleteMessageById`/`moveToDeadLetterQueue` return `void`, so `MessageService` sets
  `success=true` unless a `JMSException` escapes. The repository knows — it prints
  "No matching message found for deletion" and discards it. Only `resendMessage` checks.
  `MessageServiceIT` has `testResendMessage_Missing` but no delete/DLQ equivalent, so the
  gap in coverage mirrors the gap in behaviour exactly.
- **`DLQ_NAME = "DLQ_test"` is hardcoded** in `JmsMessageRepository`. "Move to DLQ" writes
  to an app-invented address, not the broker's real `DLQ`. `MessageServiceIT` asserts
  against the constant itself, so it passes whatever the constant says.
- **Browsing a queue that does not exist auto-creates the address**, so typos permanently
  pollute the queue list on the dev broker.
- **The Jolokia envelope leaks to the client.** `QueueController.fetchQueueNamesWithJolokia`
  returns `JolokiaResponse` verbatim, so the browser receives Jolokia's
  `request`/`value`/`timestamp`/`status` wrapper rather than a clean list. The Angular
  client reads `.value`. Unwrapping it is a breaking API change and needs a coordinated
  frontend edit — deliberately deferred, not an oversight.
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
