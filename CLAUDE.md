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

## Architecture

Standard controller → service → repository layering under
`com.message.inspector.message_inspector`. Both controllers are mapped under `/api`.

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

- **`JmsQueueRepository.fetchQueuesWithArtemis()` returns hardcoded placeholder queues**
  (`test.queue.1..3`) — it opens a connection but never uses it. The `/api/queues/artemis-straight`
  endpoint is therefore a stub; Jolokia is the working queue-discovery path.
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
