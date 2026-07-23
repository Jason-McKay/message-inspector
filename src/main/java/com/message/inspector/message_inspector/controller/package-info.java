/**
 * HTTP entry points — the thin edge of the app.
 *
 * <p>Controllers map URLs to {@code service} calls and turn the result into a
 * {@link org.springframework.http.ResponseEntity}. They hold no business logic; that lives
 * in {@code service}, and anything touching an external system lives under
 * {@code integration}.
 *
 * <p>Two things here are less obvious than a controller usually is:
 * <ul>
 *   <li>{@link com.message.inspector.message_inspector.controller.ApiExceptionHandler} is a
 *       {@code @RestControllerAdvice} that maps escaping exceptions to truthful status
 *       codes ({@code IllegalArgumentException} → 400, {@code JMSException} and
 *       {@code JolokiaAccessException} → 502). Without it, a server error is forwarded to
 *       {@code /error} and — because the security chain guards that path — comes back as a
 *       misleading 403.</li>
 *   <li>The mutating message endpoints return <strong>HTTP 200 even when the operation
 *       failed</strong>, with the real outcome in the body as
 *       {@code {"success": false, "message": "..."}}. A 200 is not proof of success; the
 *       body's {@code success} flag is. See {@code dto/ApiResponse}.</li>
 * </ul>
 */
package com.message.inspector.message_inspector.controller;
