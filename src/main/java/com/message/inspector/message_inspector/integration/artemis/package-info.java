/**
 * JMS access to the ActiveMQ Artemis broker — everything at the <em>message</em> level.
 *
 * <p>Uses {@code JmsTemplate}, {@code QueueBrowser} and message selectors to browse a
 * queue, fetch one message by ID, republish it, selectively consume (delete) it, and move
 * it to a dead-letter queue. JMS is the natural fit for these because they are all
 * message-addressed operations; it is <em>not</em> used for queue discovery, which JMS
 * cannot do (see {@code integration.jolokia} and ADR 0001).
 *
 * <p>Two things to know before editing here:
 * <ul>
 *   <li>Destination names must come from {@code Queue#getQueueName()}, not
 *       {@code toString()} — the latter yields {@code ActiveMQQueue[DLQ]} and misroutes a
 *       resend to a literally-named bad address.</li>
 *   <li>Message IDs are validated against a {@code SAFE_MESSAGE_ID} guard before going into
 *       a selector, to reject selector-injection input.</li>
 * </ul>
 *
 * <p>Queue <em>discovery</em> is deliberately absent here: JMS cannot enumerate queues, so
 * that lives in {@code integration.jolokia} (see ADR 0001). An earlier dead JMS-discovery
 * attempt ({@code JmsQueueRepository}) was removed once ADR 0001 settled the split.
 */
package com.message.inspector.message_inspector.integration.artemis;
