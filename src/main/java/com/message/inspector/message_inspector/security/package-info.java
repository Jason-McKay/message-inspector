/**
 * Authentication and authorization — stateless JWT bearer tokens.
 *
 * <p>Every request except login, {@code /error} and the dev-only Swagger routes must carry
 * a valid bearer token. The token is issued at {@code POST /api/auth/login} and validated
 * on every other request by the Spring Security resource-server filter chain configured in
 * {@link com.message.inspector.message_inspector.security.SecurityConfig}. There is no
 * server-side session. See ADR 0005 for why bearer-not-cookie.
 *
 * <p>Pieces:
 * <ul>
 *   <li>{@code JwtConfig} — the Nimbus HS256 encoder/decoder (from the resource-server
 *       starter, no third-party JWT library).</li>
 *   <li>{@code TokenService} — mints a token with the user's {@code roles} claim.</li>
 *   <li>{@code UserConfig} — an in-memory user store ({@code admin}/ADMIN,
 *       {@code jason}/USER, BCrypt). A database is deferred.</li>
 * </ul>
 *
 * <p>Role gating on the destructive endpoints is done with method security
 * ({@code @PreAuthorize} on the controller), not path matchers, because {@code move-to-dlq}
 * sits mid-path where Spring's {@code PathPattern} forbids {@code **}. CSRF is disabled
 * safely — a bearer token in a header is not a CSRF vector; if auth ever moves to cookies,
 * that must be revisited (ADR 0005).
 */
package com.message.inspector.message_inspector.security;
