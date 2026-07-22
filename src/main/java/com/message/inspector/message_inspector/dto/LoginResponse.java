package com.message.inspector.message_inspector.dto;

import lombok.Getter;

import java.util.List;

/**
 * Returned on a successful login. Carries the signed JWT the client attaches as a bearer
 * token, plus the username and roles so the UI can render identity without decoding the
 * token itself.
 */
@Getter
public class LoginResponse {
  private final String token;
  private final String username;
  private final List<String> roles;

  public LoginResponse(String token, String username, List<String> roles) {
    this.token = token;
    this.username = username;
    this.roles = roles;
  }
}
