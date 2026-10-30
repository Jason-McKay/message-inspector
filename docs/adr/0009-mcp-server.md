# 9. AI agents get an MCP server on the existing JWT chain

- **Status:** Accepted
- **Date decided:** 2026-10-30
- **Date recorded:** 2026-10-30, written as the decisions were made

## Context

Until now the API had one client, the Angular UI. An AI agent such as Claude Code is a
different kind of client:

- It learns what it can do from descriptions, not from code someone wrote against the API.
- It guesses inputs.
- It may retry or loop.
- Everything it reads lands in a finite context window.

The Model Context Protocol (MCP) is what such clients speak.

The constraints were fixed going in:

- The REST API and the Angular contract stay unchanged.
- Spring Boot 4.1.x and Java 21 ([0008](0008-spring-boot-4-1.md)).
- No JMS or Jolokia logic is duplicated.
- Destructive operations follow the same ADMIN rule as REST.
- Every destructive call is auditable.
- Nothing is open by default.

## Decision

**Add Spring AI's `spring-ai-starter-mcp-server-webmvc`, serving Streamable HTTP with
`protocol=STATELESS` at `/mcp`, behind the existing `SecurityConfig` chain.** There are six
tools, in a new `agent/` package. Each one is a thin call into the same `service` method
the REST API uses.

### Library: Spring AI 2.0.2

- **Version.** 2.0.2 is GA and built on Spring Boot 4.1.2, which is exactly our parent.
  2.1.x targets Boot 4.2, so it is out of scope.
- **SDK.** It brings the MCP Java SDK 2.1.0.
- **Tools are annotated methods.** A tool is an `@McpTool` method on an ordinary bean, so
  `@PreAuthorize` applies to it exactly as it does to a controller method.

### Transport: Streamable HTTP, stateless

- **Not stdio.** A stdio server is a separate process the client launches. It would sit
  outside the HTTP security chain, need its own broker credentials, and have no caller
  identity. That means no JWT, no ADMIN rule, and an audit trail that could only say "the
  process".
- **Not SSE.** The MCP specification replaced the HTTP+SSE transport with Streamable HTTP.
- **Stateless rather than stateful Streamable HTTP.** This matches
  [0005](0005-jwt-bearer-over-cookie-session.md):
  - The server keeps no session map.
  - Every JSON-RPC POST carries its own bearer token and is checked against it.
  - So an MCP session can neither outlive a token nor run under someone else's identity.

  The cost is no server-to-client notifications, sampling, elicitation or progress. Six
  request/response tools need none of them. Stateful `STREAMABLE` was the fallback if a
  client misbehaved. It was not needed: the SDK client in `McpServerIT`, MCP Inspector and
  Claude Code all work against the stateless server.

### Identity: how the caller reaches a tool

1. The client logs in with `POST /api/auth/login`. It sends `Authorization: Bearer <jwt>`
   on every POST to `/mcp`.
2. `/mcp` falls under `anyRequest().authenticated()`. A request without a valid token gets
   a **401** with `WWW-Authenticate: Bearer` before the MCP router runs, so no new matcher
   was needed. `SecurityConfigTest.testMcpRequiresAuthentication` and `McpServerIT` pin
   this.
3. The stateless WebMVC transport runs a sync tool on the servlet request thread.
   `SecurityContextHolder` therefore still holds the `JwtAuthenticationToken` when the
   tool's method-security proxy is consulted.
4. The admin tools carry `@PreAuthorize("hasRole('ADMIN')")`, the same expression as on
   `MessageController`.
   - Spring AI reads the tool metadata from the target class but invokes the bean it was
     given, which is the proxy.
   - `AgentToolSecurityTest.testAdminToolsAreProxied` pins this. If it ever regressed, the
     role check would vanish silently, with no error anywhere.
5. The agent sees a denial as an `isError` tool result, not as a 403. The HTTP exchange
   itself succeeded.
6. The audit user is `Authentication.getName()`, which is the token's `sub`.

### Tools: read-only and admin are separate classes

| Class | Tools | Who | Hints |
|---|---|---|---|
| `QueueInspectionTools` | `list_queues`, `browse_messages`, `get_message` | any authenticated user | `readOnly` |
| `QueueAdminTools` | `resend_message`, `delete_message`, `move_message_to_dlq` | **ADMIN** | `destructive` on delete/move; `idempotent=false` on resend |

Keeping the two sets in separate classes makes the ADMIN rule visible from the file list.
The MCP tool hints let a client ask for confirmation on the destructive tools without
reading their descriptions.

**Resend is ADMIN-only over MCP, although REST lets a USER resend.** This divergence is
deliberate:

- Resend is the one non-idempotent operation. Every call adds another copy.
- An agent that retries on a timeout, or loops, multiplies messages.
- REST is unchanged.

### Agent guardrails (MCP only, so REST is unchanged)

- **Queue names are checked against the broker's list before any JMS call.** Artemis
  auto-creates an address when a missing queue is browsed, so a guessed name would
  permanently pollute the queue list.
  - `McpServerIT.testUnknownQueueCreatesNoAddress` proves no address appears.
  - The cost is one Jolokia call per tool call.
- **Errors are fixed text, never an exception message.**
  - Spring AI's default turns an exception into an error result containing `getMessage()`
    and the root cause.
  - `requireValidMessageId` puts the rejected ID in its message, and JMS exceptions carry
    broker internals.
  - So the tools catch these exceptions themselves and return fixed text that tells the
    agent what to do next.
- **Output is bounded.**
  - `browse_messages` pages: default 20, maximum 100, out-of-range values clamped, and the
    page reports the limit actually applied.
  - Payloads are cut to 500 characters on browse and 8000 on `get_message`, with
    `payloadTruncated` and `payloadLength`.
  - The limits are constants concatenated into the tool descriptions, so the documented and
    enforced values cannot drift apart.
- **The agent gets its own DTOs** (`AgentMessageDto`, `AgentMessagePageDto`). `JmsMessageDto`
  is untouched, so the UI contract cannot move.

### Audit

Every admin tool call writes exactly one line, with `System.out.println` per the repo
convention:

```
AUDIT agent-tool=move_message_to_dlq user="admin" queue="orders" messageId="ID:..." outcome=success result="Message moved to DLQ successfully."
```

`outcome` takes one of four values:

- `success`
- `failed`: the service ran and reported failure, including "Message not found."
- `rejected`: refused before any broker call, because of an unknown queue or a malformed ID
- `denied`: method security said no

**Denied calls never reach the method body**, so the tool cannot log them itself. Two pieces
cover that:

- `SecurityConfig` now registers a `SpringAuthorizationEventPublisher`. Without it, Spring
  Security drops authorization events.
- `AgentAuthorizationAuditListener` audits every `AuthorizationDeniedEvent` for an
  `@McpTool` method.

**Every caller-supplied value is sanitised before it is logged.** It is quoted, escaped,
capped at 256 characters, and has its control characters replaced. Otherwise a message ID
containing a newline could forge a second `AUDIT` line. The tools' diagnostic lines echo
exception messages, which contain the rejected ID, so they go through the same sanitiser.

### On in every profile, not dev-only

The repo's rule for dev-only features ([0006](0006-dependency-maintenance-workflow.md))
exists for Swagger, which serves unauthenticated JavaScript with known findings. MCP is a
second API, guarded by the same chain as the first. It therefore follows the REST API, which
is on everywhere and authenticated, rather than Swagger.

`SPRING_AI_MCP_SERVER_ENABLED=false` removes the endpoint entirely:

- An authenticated request then gets a 404.
- An unauthenticated one still gets a 401.

### Dependency

The OWASP gate ([0006](0006-dependency-maintenance-workflow.md)) was run before and after the
starter was added:

- The two reports are identical.
- The new tree is clean: MCP SDK, Spring AI model, json-schema-validator,
  jsonschema-generator, ANTLR, StringTemplate, jtokkit, and others.
- No pin or suppression was needed.

Two exclusions were needed:

- The deprecated `spring-boot-starter-web` alias, which adds nothing over `starter-webmvc`.
- `swagger-annotations-jakarta`. Spring AI brings 2.2.38 on a shorter path than springdoc's
  swagger-core 2.2.55, so Maven's nearest-wins rule was silently downgrading it. This
  exclusion carries an EXPIRY note in the POM, as [0002](0002-dependency-version-overrides-expire.md)
  requires.

## Consequences

**Accepted costs:**

- **Browse still reads the whole queue.** The page limit bounds what the agent receives,
  not what the broker is asked for. The REST API has the same cost.
- **A non-text message fails the whole browse**, as it does over REST. The agent is told the
  queue holds something unreadable.
- **Tokens last an hour, and the client holds a static header.** Spring Security 7's
  resource server already serves RFC 9728 protected-resource metadata
  (`/.well-known/oauth-protected-resource`) and points to it from the 401's
  `WWW-Authenticate` header. That behaviour predates this change. The document names no
  authorization server, so a client still cannot fetch or refresh its own token. After an
  hour you log in again and re-add the header. The `spring-ai-community/mcp-security`
  project was considered. It assumes an OAuth2 authorization server, which
  [0005](0005-jwt-bearer-over-cookie-session.md) deliberately does not have.
- **Moved and resent copies get a new messageId and carry only the payload.** This is the
  existing `sendMessage` behaviour. The tool descriptions say so, so the agent reads
  properties before it moves a message.
- **The authorization event publisher also publishes REST denials.** Nothing listens for
  them, and publishing changes no decision.
- **Spring AI logs WARNs at startup**: `BeanPostProcessorChecker` on its own scanner beans,
  and "No resource/prompt/complete methods found" even though those capabilities are off.
  They are cosmetic. The proxy test is what proves our own beans are still proxied.
- **The dependency tree is larger**, which gives OWASP more to scan. The nightly gate covers
  it.

**Why it is still right:** an agent gets the same broker operations, under the same identity
and the same ADMIN rule as the UI, through the same service layer. It also gets guardrails
the UI never needed, and an audit trail for destructive calls. None of it required changing
a REST route, a response shape, or the security chain's rules.

## Alternatives considered

- **stdio transport.** Rejected: there would be no caller identity (see above).
- **A separate MCP process that calls the REST API.** This would keep identity by forwarding
  the token. Rejected: it adds a second deployable, copies the REST contract into tool
  definitions, and adds a network hop, all to do what the app can host in-process.
- **Stateful Streamable HTTP or SSE.** SSE is superseded. Stateful sessions buy features no
  tool here uses, and they add server state that [0005](0005-jwt-bearer-over-cookie-session.md)
  avoids.
- **The raw MCP Java SDK without Spring AI.** Rejected: it means hand-wiring the transport,
  the router and the tool schemas. Spring AI does all three, and it puts tools on proxied
  beans, which is what makes `@PreAuthorize` work.
- **Making MCP dev-only.** Rejected: see "On in every profile" above.
