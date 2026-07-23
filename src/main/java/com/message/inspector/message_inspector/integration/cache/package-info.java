/**
 * Redis access — the per-user last-used-queue preference.
 *
 * <p>{@code RedisQueueRepository} stores a single string per user (the queue they were last
 * looking at), keyed by the authenticated username. It is named {@code cache} for the role
 * it plays rather than for Redis specifically. Why Redis and not browser {@code
 * localStorage}: the preference is a fact about the <em>user</em>, and identity is
 * server-side, so it must survive a change of device — see ADR 0003.
 *
 * <p>Absence of a preference is represented as an empty value (surfacing as an empty HTTP
 * 200 upstream), not an error — a first-time user is the normal case.
 */
package com.message.inspector.message_inspector.integration.cache;
