# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

**This file holds the rules; the reasons are in `docs/`.** Until 2026-10-31 the incident or
fix behind each rule was in here too, loaded into every session; it moved word for word into
the files below, and here each rule is a line or two. **Read a rule's reason before changing
or deleting it.** New reasoning goes in the matching `docs/` file, not here. Decisions are in
`docs/adr/`, which the split did not touch.

| Topic | Reasons in |
|---|---|
| The Docker API pin, the `*IT` split, the self-contained ITs, the manual JMS pass, the orphaned JVM | `docs/testing.md` |
| Queue discovery, truthful results, the DLQ name, `ArtemisConfig`, selectors, security, destination names, error mapping, the MCP server | `docs/gotchas.md` |

## What this is

Backend of Message Inspector: a Spring Boot 4.1 / Java 21 tool to browse, resend, delete,
and DLQ-move messages on an ActiveMQ Artemis broker. It is the server half of a two-repo
full-stack app:

- Backend: this repo.
- Frontend: `message-inspector-ui` (Angular 22.2.4, Node 24.18.0, :4200).
- Shared dev infra: `infrastructure` (separate repo, local only).
- Roadmap: a local planning doc kept outside the repo — phased plan with the
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

**The compose stack and the shared infra cannot both be up as-is**: compose's `artemis`
publishes host :61616/:8161, which `dev-artemis` already holds. Either stop the shared infra
or add a throwaway override that remaps them (`ports: !override` with `61617:61616` and
`8162:8161`). The app still reaches `artemis:61616` in-network either way.

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

Rules, with the reasons in `docs/testing.md`:

- **Several tests use Testcontainers and need Docker running.** Do not remove the surefire
  `argLine` pin `-Dapi.version=1.44` in `pom.xml`; without it Docker Engine 29+ fails with
  the misleading "Could not find a valid Docker environment".
- **`./mvnw test` does not run `MessageServiceIT` or `McpServerIT`** — deliberate, so
  `mvn test` stays fast and unit-only. **Use `./mvnw verify`** to run them via failsafe, not
  an explicit `-Dtest=...` filter, which runs them without failsafe's setup.
- **The two ITs are self-contained**: each starts its own Artemis container
  (`apache/activemq-artemis:2.31.2`), needs Docker but not the shared infra, and does not
  touch the dev broker. They are the only automated tests that open a JMS connection —
  everything else at the message level is Mockito-based.
- **The whole `./mvnw test` suite passes with the shared infra down**, given Docker is up.
  `SecurityConfigTest` is broker-independent.
- **Verify JMS changes by hand.** Boot the app, produce with
  `MSYS_NO_PATHCONV=1 docker exec dev-artemis /opt/activemq-artemis/bin/artemis producer
  --url tcp://localhost:61616 --destination queue://DLQ --message-count 3 --user admin
  --password admin`, then exercise browse / get-by-id / delete / resend / move-to-dlq.
  This pass has caught real bugs the suite could not see. Clean up afterwards: purge the
  DLQ and delete any `DLQ_test` or `ActiveMQQueue[DLQ]` addresses it leaves behind.
- **`spring-boot:run` in the background outlives its Maven wrapper.** Kill the `java` process
  holding :8080 by port, not the wrapper.

## Architecture

Layered packages under `com.message.inspector.message_inspector`. Both controllers are
mapped under `/api`; the MCP tools are served at `/mcp` (ADR 0009).

```
agent/        QueueInspectionTools, QueueAdminTools, ToolAudit, ToolResults,
              AgentAuthorizationAuditListener                      (MCP tools)
config/       Application, Artemis, Redis, RestTemplate, Swagger wiring
controller/   MessageController, QueueController
dto/          ApiResponse, JmsMessageDto, JolokiaResponse, AgentMessageDto, AgentMessagePageDto
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

Rules, with the reasons in `docs/gotchas.md`:

- **Only one queue-discovery route exists: `/api/queues/artemis-jolokia/names`.** It returns
  a bare JSON array; the Jolokia envelope is unwrapped in `JolokiaRepository` and never
  crosses the repository boundary.
- **`delete`, `move-to-dlq` and `resend` all report truthfully on a missing message**:
  `success=false, "Message not found."`, never a false success. `MessageServiceIT` covers all
  three.
- **The DLQ target is the `message-inspector.dlq-name` property (default `DLQ`)**, read by
  `JmsMessageRepository` — not a hardcoded address.
- **Browsing a queue that does not exist auto-creates the address**, so typos permanently
  pollute the queue list on the dev broker.
- **`ArtemisConfig` builds its own `ConnectionFactory`** rather than using Boot's, which
  `spring.main.allow-bean-definition-overriding=true` is what permits. It reads
  `spring.artemis.broker-url`/`user`/`password`; Jolokia credentials
  (`JOLOKIA_USER`/`JOLOKIA_PASSWORD`) are wired via `RestTemplateConfig`.
- **JMS selectors are built by string concatenation.** `JmsMessageRepository` guards every
  such path with `requireValidMessageId` against `SAFE_MESSAGE_ID`. Any new selector-taking
  method must call it too — without it, an ID like `x' OR '1'='1` widens the selector and
  the wrong message gets consumed or DLQ'd.
- **`SecurityConfig` requires a bearer token on everything** except `/api/auth/login`,
  `/error` and the dev-only Swagger routes. Stateless JWT, so CSRF stays disabled (that
  changes only if auth moves to cookies). ADMIN gating is **method security** on
  `MessageController`, not path matchers — PathPattern rejects a `**` mid-path.
- `jolokiaRestTemplate` registers a Jackson converter for `text/plain` because Jolokia
  returns JSON under that content type.
- **Destination names must come from `getQueueName()`, not `toString()`**, which yields
  `ActiveMQQueue[DLQ]` on Artemis. `MessageServiceIT` pins it; the mock-based tests cannot.
- **Error responses**, in `ApiResponse`'s shape via `ApiExceptionHandler`: the
  `SAFE_MESSAGE_ID` guard → **400** without echoing the input; `JMSException` and
  `JolokiaAccessException` → **502**; anything else → **500** via `/error`, which must stay
  permitted or every server error becomes a misleading 403.
- **MCP server (`agent/`, ADR 0009).** Spring AI's WebMVC starter serves stateless
  Streamable HTTP at `/mcp`, on in every profile, under `anyRequest().authenticated()`, so a
  call without a token gets a 401 before any tool runs. Things that are easy to break:
  - **`@PreAuthorize` on `QueueAdminTools` only works because Spring AI invokes the proxy.**
    Do not make the class or its methods `final` or register tools another way;
    `AgentToolSecurityTest.testAdminToolsAreProxied` pins it.
  - **Denials are audited by an event listener, not by the tool.** Remove `SecurityConfig`'s
    `AuthorizationEventPublisher` bean and `outcome=denied` lines stop with no error anywhere.
  - **Every tool that takes a queue name checks it against `fetchQueueNamesWithJolokia()`
    first** (the auto-create gotcha above). A new tool must do the same.
  - **Tools catch their own exceptions and return fixed text.** Anything caller-supplied
    that reaches a log line goes through `ToolAudit.quote`, including exception messages.
  - **Resend is ADMIN-only over MCP but not over REST.** This is deliberate: it is not
    idempotent and agents retry. Do not "fix" either side to match.
  - **Kill switch:** `SPRING_AI_MCP_SERVER_ENABLED=false` removes `/mcp`, giving a 404 when
    authenticated and a 401 when not. REST is unaffected.
  - Spring AI's startup WARNs are upstream noise; the proxy test is what matters.

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
