package com.message.inspector.message_inspector.security;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authorization.AuthorizationEventPublisher;
import org.springframework.security.authorization.SpringAuthorizationEventPublisher;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Phase 4 security: stateless JWT bearer authentication.
 * <p>
 * Every request except login, {@code /error} and the (dev-only) Swagger routes must carry
 * a valid bearer token. Role gating for the destructive endpoints is done with method
 * security ({@code @PreAuthorize} on {@code MessageController}) rather than path matchers,
 * because {@code move-to-dlq} sits mid-path and Spring's PathPattern does not allow
 * {@code **} in the middle of a pattern.
 * <p>
 * The MCP endpoint ({@code /mcp}, ADR 0009) needs no matcher of its own: it falls under
 * {@code anyRequest().authenticated()} like the REST API, and its admin tools are gated by
 * the same {@code @PreAuthorize} on {@code agent/QueueAdminTools}.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http
      // Picks up the CorsConfigurationSource bean below and places CorsFilter inside the
      // security chain, ahead of authorization, so preflight is answered without auth.
      .cors(Customizer.withDefaults())
      // Safe: auth is a stateless bearer token, not a cookie session, so there is no CSRF
      // vector. The Phase 4 tripwire the earlier comment warned about did not fire -- JWT
      // was chosen precisely so this can stay disabled without opening a hole. If auth ever
      // moves to cookies, re-enable CSRF and revisit setAllowCredentials below.
      .csrf(csrf -> csrf.disable())
      // No server-side session: every request is authenticated from its own token.
      .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
      .authorizeHttpRequests(auth -> auth
        .requestMatchers(
            // Login must be reachable without a token -- it is how you get one.
            "/api/auth/login",
            // /error must be permitted or the container's forward to it is itself rejected
            // by this chain, turning every unhandled server error into a 403. That masking
            // is especially dangerous now that 403 is a legitimate answer.
            "/error",
            // Dev-facing API docs. springdoc is disabled outside the dev profile
            // (springdoc.*.enabled, see application.properties), so these matchers point
            // at routes that 404 in prod -- permitting a nonexistent route is harmless,
            // and profile-conditional filter chains would be disproportionate machinery.
            "/api/swagger-ui.html",
            "/api/swagger-ui/**",
            "/api/api-docs/**",
            "/swagger-ui/**",
            "/v3/api-docs/**")
          .permitAll()
        // Everything else -- including the whole message and queue API -- now requires a
        // valid token. This is what closes the Phase 2 permit-all on /api/**.
        .anyRequest().authenticated()
      )
      .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
    return http.build();
  }

  /**
   * Maps the token's {@code roles} claim (bare names like {@code ADMIN}) to Spring's
   * {@code ROLE_}-prefixed authorities, so {@code hasRole('ADMIN')} works as expected.
   */
  private JwtAuthenticationConverter jwtAuthenticationConverter() {
    JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
    authorities.setAuthoritiesClaimName("roles");
    authorities.setAuthorityPrefix("ROLE_");

    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(authorities);
    return converter;
  }

  /**
   * Publishes authorization denials as {@code AuthorizationDeniedEvent}s; without a publisher
   * bean Spring Security discards them. The MCP admin tools need the event: a call that
   * {@code @PreAuthorize} rejects never reaches the method, so the event is the only place
   * to audit it (see {@code agent/AgentAuthorizationAuditListener}). Publishing changes no
   * decision. REST denials are published too and nothing listens for them.
   */
  @Bean
  public AuthorizationEventPublisher authorizationEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
    return new SpringAuthorizationEventPublisher(applicationEventPublisher);
  }

  @Bean
  public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(List.of("http://localhost:4200"));
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(List.of("*"));
    // Bearer auth needs no credentials mode: the token rides in the Authorization header
    // (allowed by "*" above), not a cookie. Leaving this false removes the CSRF-hole
    // precondition the cookie approach would have created.
    config.setAllowCredentials(false);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return source;
  }
}
