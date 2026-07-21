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
./mvnw test
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
- `MessageServiceIT` **never runs**. There is no failsafe plugin and surefire does not
  match `*IT`, so the reported test count excludes it. It is a real `@SpringBootTest`
  against a live broker with a pre-seeded `testQueue`, so it will not pass unattended.
  (Passing an explicit `-Dtest=...` filter overrides surefire's includes and *will* drag
  it into the run, where it fails without a broker. Not a regression.)
- **No test opens a JMS connection**, so a green suite says nothing about broker
  behaviour. `SecurityConfigTest` is the only test that exercises the real filter chain
  and the live Jolokia path — which also means **it fails if the infra is down**, and
  that reads as a test failure rather than a missing container. Bring `../infrastructure`
  up before trusting a red run.
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
  Jolokia is the only working queue-discovery path. Note the mock-based tests cannot see
  this — they stub the layer that fails.
- **The Jolokia envelope leaks to the client.** `QueueController.fetchQueueNamesWithJolokia`
  returns `JolokiaResponse` verbatim, so the browser receives Jolokia's
  `request`/`value`/`timestamp`/`status` wrapper rather than a clean list. The Angular
  client reads `.value`. Unwrapping it is a breaking API change and needs a coordinated
  frontend edit — deliberately deferred, not an oversight.
- **`ArtemisConfig` hardcodes the broker URL and credentials** and builds its own
  `ConnectionFactory`, so the `spring.artemis.*` properties (which do read from
  `ARTEMIS_USER`/`ARTEMIS_PASSWORD`) are effectively inert for the `JmsTemplate` path.
  `spring.main.allow-bean-definition-overriding=true` is what lets this coexist with
  Boot's autoconfiguration. Jolokia credentials (`JOLOKIA_USER`/`JOLOKIA_PASSWORD`) *are*
  wired properly, via `RestTemplateConfig`.
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
- **`resend` delivers to a malformed queue name.** `convertToDto` sets `queueName` from
  `message.getJMSDestination().toString()`, which yields `ActiveMQQueue[DLQ]`, not `DLQ`.
  `MessageService.resendMessage` feeds that back into `sendMessage`, so a resend creates
  and lands in an address literally named `ActiveMQQueue[DLQ]`. The API still reports
  `success=true`. Confirmed against a live broker 2026-07-21; unfixed, tracked in the
  plan's known gaps.
- **Unhandled exceptions reach the client as 403, not 500.** `/error` is not in
  `SecurityConfig`'s permit list, so when a controller throws, the forward to `/error`
  is rejected by security and the caller sees a permission error. Verified: an invalid
  message ID throws `IllegalArgumentException` from the `SAFE_MESSAGE_ID` guard and the
  response is 403. Do not read a 403 from this API as an auth problem without checking
  the server log.

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
