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

    // Strip the ROLE_ prefix Spring adds to authorities so the token carries bare role
    // names; the resource-server converter re-adds the prefix when it reads the token back.
    List<String> roles = authentication.getAuthorities().stream()
      .map(GrantedAuthority::getAuthority)
      .map(authority -> authority.startsWith("ROLE_") ? authority.substring("ROLE_".length()) : authority)
      .toList();

    String token = tokenService.issue(authentication.getName(), roles);
    return ResponseEntity.ok(new LoginResponse(token, authentication.getName(), roles));
  }
}
