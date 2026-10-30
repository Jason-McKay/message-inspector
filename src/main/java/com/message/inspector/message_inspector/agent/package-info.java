/**
 * MCP entry points — the edge AI agents use, beside {@code controller} for HTTP clients.
 *
 * <p>The classes here are tools on the app's MCP server (Spring AI, Streamable HTTP at
 * {@code /mcp}, stateless). Like controllers they hold no business logic: every tool calls
 * the same {@code service} method the REST API does. What they add is what an agent needs
 * and a browser does not — see ADR 0009:
 * <ul>
 *   <li><strong>Identity is the REST API's.</strong> {@code /mcp} sits behind the same
 *       {@code SecurityConfig} chain, so a client sends the bearer JWT from
 *       {@code /api/auth/login} on every request and an unauthenticated one is answered 401
 *       before any tool runs. Tools run on the request thread, so method security sees that
 *       token.</li>
 *   <li><strong>Read-only and admin tools are separate classes.</strong>
 *       {@code QueueAdminTools} carries
 *       {@code @PreAuthorize("hasRole('ADMIN')")} on every method, as
 *       {@code MessageController} does for delete and move. Resend is ADMIN-only here even
 *       though REST lets a USER resend: it is the one non-idempotent tool, and an agent can
 *       loop.</li>
 *   <li><strong>Every admin call writes an {@code AUDIT} line</strong>, including the ones
 *       method security denies, which never reach the method body and are caught by
 *       {@code AgentAuthorizationAuditListener} instead.</li>
 *   <li><strong>Queue names are checked against the broker's list before any JMS
 *       call</strong>, because browsing a queue that does not exist auto-creates the address
 *       and a model will happily guess a name.</li>
 *   <li><strong>Errors are controlled text, never an exception message.</strong> The
 *       {@code SAFE_MESSAGE_ID} guard puts the rejected ID in its message, and broker
 *       exceptions carry internals; the framework's default error result would forward
 *       both.</li>
 *   <li><strong>Output is bounded</strong> — a page-size limit on browse, and payloads cut to
 *       a fixed length with a flag saying so — so one call cannot flood the agent's
 *       context.</li>
 * </ul>
 */
package com.message.inspector.message_inspector.agent;
