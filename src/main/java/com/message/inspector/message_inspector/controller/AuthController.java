package com.message.inspector.message_inspector.controller;

import com.message.inspector.message_inspector.dto.LoginRequest;
import com.message.inspector.message_inspector.dto.LoginResponse;
import com.message.inspector.message_inspector.security.TokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Issues JWTs in exchange for valid credentials. This is the only unauthenticated endpoint
 * under {@code /api} -- it is how a client obtains the token every other call requires.
 * <p>
 * Bad credentials raise an {@code AuthenticationException}, which {@code ApiExceptionHandler}
 * maps to 401.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

  private final AuthenticationManager authenticationManager;
  private final TokenService tokenService;

  @PostMapping("/login")
  public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request) {
    Authentication authentication = authenticationManager.authenticate(
      new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword()));

    // Keep only the application's roles. Spring Security 7 also grants a FACTOR_*
    // authority for the password factor; that is not a role the UI knows about, and
    // copying it into the token would change the login payload. Strip the ROLE_ prefix
    // so the token carries bare names; the resource-server converter re-adds it.
    List<String> roles = authentication.getAuthorities().stream()
      .map(GrantedAuthority::getAuthority)
      .filter(authority -> authority.startsWith("ROLE_"))
      .map(authority -> authority.substring("ROLE_".length()))
      .toList();

    String token = tokenService.issue(authentication.getName(), roles);
    return ResponseEntity.ok(new LoginResponse(token, authentication.getName(), roles));
  }
}
