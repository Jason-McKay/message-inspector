package com.message.inspector.message_inspector.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

/**
 * The user store for Phase 4: two in-memory accounts, no database.
 * <p>
 * {@code admin} (role ADMIN) can do everything including the destructive delete/DLQ moves;
 * {@code jason} (role USER) can browse and resend but not delete or move to the DLQ -- see
 * the {@code @PreAuthorize} annotations on those endpoints. Passwords come from config with
 * dev defaults, so nothing but the throwaway local values is committed. A DB-backed store
 * is deliberately deferred to a later phase.
 */
@Configuration
public class UserConfig {

  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  public UserDetailsService userDetailsService(
      PasswordEncoder passwordEncoder,
      @Value("${message-inspector.users.admin-password:admin}") String adminPassword,
      @Value("${message-inspector.users.user-password:jason}") String userPassword) {
    UserDetails admin = User.withUsername("admin")
      .password(passwordEncoder.encode(adminPassword))
      .roles("ADMIN")
      .build();
    UserDetails user = User.withUsername("jason")
      .password(passwordEncoder.encode(userPassword))
      .roles("USER")
      .build();
    return new InMemoryUserDetailsManager(admin, user);
  }

  @Bean
  public AuthenticationManager authenticationManager(
      UserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
    // Spring Security 7 dropped the no-arg constructor and setUserDetailsService.
    DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
    provider.setPasswordEncoder(passwordEncoder);
    return new ProviderManager(provider);
  }
}
