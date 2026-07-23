/**
 * Data transfer objects — the app's wire format, and (since there is no {@code domain}
 * package) its only model.
 *
 * <p>These classes are what the JSON API serialises. Two carry more meaning than a plain
 * bag of fields:
 * <ul>
 *   <li>{@link com.message.inspector.message_inspector.dto.ApiResponse} — the
 *       {@code {success, message}} envelope every mutating endpoint returns. Because those
 *       endpoints answer HTTP 200 even on a business failure, this body is the real
 *       result. See {@code controller}.</li>
 *   <li>{@link com.message.inspector.message_inspector.dto.JolokiaResponse} — the raw JMX
 *       envelope Jolokia sends back ({@code request/value/timestamp/status}). It is an
 *       integration-shaped DTO: it is deserialised inside {@code integration/jolokia} and
 *       deliberately unwrapped there so the envelope no longer reaches the client (ADR
 *       0001, resolved Phase 5).</li>
 * </ul>
 *
 * <p>There is intentionally no {@code domain} or {@code mapper} package — the DTOs are the
 * whole model until a real domain model exists. See ADR 0004.
 */
package com.message.inspector.message_inspector.dto;
