package com.message.inspector.message_inspector.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Wires the HMAC-SHA256 JWT encoder and decoder off a single shared secret.
 * <p>
 * Symmetric signing (HS256) keeps this simple for in-memory auth: the same secret both
 * signs on login and verifies on every request, so there is no keypair to manage. The
 * secret comes from {@code message-inspector.jwt.secret} -- a dev default is set in
 * {@code application.properties} and MUST be overridden by env anywhere real. It has to be
 * at least 256 bits (32 bytes) or Nimbus rejects it for HS256.
 */
@Configuration
public class JwtConfig {

  private final SecretKeySpec secretKey;

  public JwtConfig(@Value("${message-inspector.jwt.secret}") String secret) {
    this.secretKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
  }

  @Bean
  public JwtEncoder jwtEncoder() {
    return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey));
  }

  @Bean
  public JwtDecoder jwtDecoder() {
    return NimbusJwtDecoder.withSecretKey(secretKey).macAlgorithm(MacAlgorithm.HS256).build();
  }
}
