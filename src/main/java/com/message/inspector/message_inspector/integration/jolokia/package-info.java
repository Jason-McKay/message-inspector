/**
 * HTTP access to the broker's management MBeans via the Jolokia bridge — queue/address
 * <em>metadata</em>.
 *
 * <p>JMS cannot list the queues on a broker; a JMS client only addresses destinations it
 * already knows. Artemis exposes that inventory over JMX, and the management console's
 * <strong>Jolokia</strong> bridge makes the same MBeans reachable over plain HTTP+JSON on
 * the console port (:8161). So this package is a {@code RestTemplate} client, not a JMS
 * client. See ADR 0001.
 *
 * <p>The working route is {@code AddressNames} (queue names), reached through
 * {@code JolokiaRepository.fetchQueueNamesWithJolokia}. It deserialises the raw JMX
 * envelope ({@code JolokiaResponse}) and <strong>unwraps it here</strong>, returning a
 * plain {@code List<String>} so the envelope never reaches the client (ADR 0001, resolved
 * Phase 5). Failures throw {@link
 * com.message.inspector.message_inspector.integration.jolokia.JolokiaAccessException},
 * which {@code controller/ApiExceptionHandler} maps to 502 — an upstream fault, like a JMS
 * failure, not a generic 500.
 */
package com.message.inspector.message_inspector.integration.jolokia;
