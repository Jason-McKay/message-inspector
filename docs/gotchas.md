# Gotchas

Moved out of `CLAUDE.md` on 2026-10-31, word for word, so it no longer loads into every
session. `CLAUDE.md` keeps each rule as a line or two and points here for the reason. Where the
text below says "this file", it means `CLAUDE.md` as it stood when the passage was written.

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
  `spring.artemis.broker-url`/`user`/`password` (URL and user default to the local stack;
  the password has no default since 2026-11-10), so
  those properties are live — they used to be hardcoded and therefore inert, which is
  why `MessageServiceIT` could only ever target `localhost:61616` and now can point at a
  container. Jolokia credentials (`JOLOKIA_USER`/`JOLOKIA_PASSWORD`) are wired via
  `RestTemplateConfig`.
- **JMS selectors are built by string concatenation.** `JmsMessageRepository` guards every
  such path with `requireValidMessageId` against `SAFE_MESSAGE_ID`. Any new selector-taking
  method must call it too — without it, an ID like `x' OR '1'='1` widens the selector and
  the wrong message gets consumed or DLQ'd.
- **`SecurityConfig` requires a bearer token on everything** except `/api/auth/login`,
  `/error` and the dev-only Swagger routes (Phase 4; the Phase 2 permit-all on `/api/**`
  is gone). Auth is stateless JWT — `oauth2ResourceServer`, `SessionCreationPolicy.STATELESS`
  — so CSRF stays disabled without opening a hole, and CORS sets `allowCredentials(false)`;
  the old tripwire about re-enabling CSRF applies only if auth ever moves to cookies.
  Role gating for the destructive endpoints is **method security**
  (`@PreAuthorize("hasRole('ADMIN')")` on `MessageController`), not path matchers, because
  `move-to-dlq` sits mid-path and PathPattern rejects a `**` in the middle. The token's
  bare `roles` claim is mapped to `ROLE_`-prefixed authorities by a
  `JwtAuthenticationConverter`.
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
- **MCP server (`agent/`, ADR 0009).** Spring AI 2.0.2's WebMVC starter serves stateless
  Streamable HTTP at `/mcp`, on in every profile. It needs no matcher of its own: it falls
  under `anyRequest().authenticated()`, so a call without a token gets a 401 before any tool
  runs. Things that are easy to break:
  - **`@PreAuthorize` on `QueueAdminTools` only works because Spring AI invokes the proxy.**
    `AgentToolSecurityTest.testAdminToolsAreProxied` pins it. Making the class or its
    methods `final`, or registering tools some other way, would remove the ADMIN check
    silently.
  - **Denials are audited by an event listener, not by the tool.** A denied call never
    enters the method. `SecurityConfig`'s `AuthorizationEventPublisher` bean is what makes
    Spring Security publish `AuthorizationDeniedEvent`; remove it and `outcome=denied` lines
    stop with no error anywhere.
  - **Every tool that takes a queue name checks it against `fetchQueueNamesWithJolokia()`
    first** (the auto-create gotcha above). A new tool must do the same.
  - **Tools catch their own exceptions and return fixed text.** Spring AI's default puts
    `getMessage()` into the result, and `requireValidMessageId`'s message echoes the ID.
    Anything caller-supplied that reaches a log line goes through `ToolAudit.quote`,
    including exception messages; a raw newline would forge an `AUDIT` line.
  - **Resend is ADMIN-only over MCP but not over REST.** This is deliberate: it is not
    idempotent and agents retry. Do not "fix" either side to match.
  - **Kill switch:** `SPRING_AI_MCP_SERVER_ENABLED=false` removes `/mcp`, giving a 404 when
    authenticated and a 401 when not. REST is unaffected.
  - Startup logs Spring AI WARNs (`BeanPostProcessorChecker` on its scanner beans, "No
    resource/prompt/complete methods found"). They are upstream noise; the proxy test is
    what matters.
