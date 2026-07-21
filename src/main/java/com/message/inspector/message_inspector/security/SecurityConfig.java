package com.message.inspector.message_inspector.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http
      // Picks up the CorsConfigurationSource bean below and places CorsFilter inside the
      // security chain, ahead of authorization, so preflight is answered without auth.
      .cors(Customizer.withDefaults())
      // Safe today: the API is stateless with no cookie-based session, so there is no
      // CSRF vector. PHASE 4 TRIPWIRE: if auth moves to cookies, this combined with
      // setAllowCredentials(true) below becomes a live CSRF hole. Re-enable CSRF then.
      .csrf(csrf -> csrf.disable())
      .authorizeHttpRequests(auth -> auth
        // PHASE 4: every controller is mapped under /api, so this permits the entire
        // API -- including the destructive message endpoints -- and makes the
        // anyRequest() rule below unreachable. Authentication is deliberately deferred;
        // this is not a hardened configuration.
        .requestMatchers(
            "/api/**",
            "/swagger-ui/**",
            "/v3/api-docs/**")
          .permitAll()
        .anyRequest().authenticated()
      );
    return http.build();
  }

  @Bean
  public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(List.of("http://localhost:4200"));
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(List.of("*"));
    config.setAllowCredentials(true);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return source;
  }
}
