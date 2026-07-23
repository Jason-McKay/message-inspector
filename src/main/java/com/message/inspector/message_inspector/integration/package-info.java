/**
 * The boundary to everything the app does not own.
 *
 * <p>Every class under {@code integration} talks to an external system, grouped by which
 * system it talks to. This package is named for the <em>role</em> — "here be process
 * boundaries" — rather than for a technology, and it deliberately replaces the flat
 * {@code repository} package a template would suggest, because this app has no persistence
 * layer and none of these are repositories in the DDD sense. See ADR 0004.
 *
 * <ul>
 *   <li>{@code integration.artemis} — the ActiveMQ Artemis broker over <strong>JMS</strong>
 *       (message-level operations: browse, get-by-id, resend, delete, move-to-dlq).</li>
 *   <li>{@code integration.jolokia} — the same broker's management face over
 *       <strong>HTTP+JSON</strong> via the Jolokia bridge (queue/address metadata, which
 *       JMS cannot enumerate). See ADR 0001.</li>
 *   <li>{@code integration.cache} — <strong>Redis</strong>, holding the per-user
 *       last-used-queue preference. See ADR 0003.</li>
 * </ul>
 *
 * <p>Each sub-package has its own client, configuration, and credentials, and its own
 * failure mode — the broker can be reachable over JMS but not Jolokia, or vice versa.
 */
package com.message.inspector.message_inspector.integration;
