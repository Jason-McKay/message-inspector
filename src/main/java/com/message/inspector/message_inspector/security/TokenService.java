package com.message.inspector.message_inspector.security;

import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Issues signed JWTs after a successful authentication.
 * <p>
 * The token carries the username as {@code sub} and a {@code roles} claim (bare role
 * names, no {@code ROLE_} prefix -- the resource-server converter in {@link SecurityConfig}
 * re-adds it). It is short-lived: a stolen token is only useful until it expires, which is
 * the mitigation for storing it client-side.
 */
@Service
@RequiredArgsConstructor
public class TokenService {

  private static final Duration TOKEN_TTL = Duration.ofHours(1);

  private final JwtEncoder jwtEncoder;

  public String issue(String username, List<String> roles) {
    Instant now = Instant.now();
    JwtClaimsSet claims = JwtClaimsSet.builder()
      .subject(username)
      .issuedAt(now)
      .expiresAt(now.plus(TOKEN_TTL))
      .claim("roles", roles)
      .build();

    JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
    return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
  }
}
