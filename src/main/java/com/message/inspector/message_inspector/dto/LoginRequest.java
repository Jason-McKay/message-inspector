package com.message.inspector.message_inspector.dto;

import lombok.Data;

/**
 * Credentials posted to {@code /api/auth/login}. Bound from the request body.
 */
@Data
public class LoginRequest {
  private String username;
  private String password;
}
