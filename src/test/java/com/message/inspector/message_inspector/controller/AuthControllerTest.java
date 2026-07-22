package com.message.inspector.message_inspector.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Exercises the login endpoint against the real in-memory user store and JWT wiring.
 * <p>
 * {@code @SpringBootTest} rather than {@code @WebMvcTest} because login needs the whole
 * auth stack -- the {@code AuthenticationManager}, the seeded users and the token encoder.
 * None of that touches the broker, so this runs without Docker.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerTest {

  @Autowired
  private MockMvc mockMvc;

  /**
   * ✅ Valid credentials return a signed token plus the identity the UI renders.
   */
  @Test
  void testLoginSucceedsAndReturnsToken() throws Exception {
    mockMvc.perform(post("/api/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"username\":\"jason\",\"password\":\"jason\"}"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.token").isNotEmpty())
      .andExpect(jsonPath("$.username").value("jason"))
      .andExpect(jsonPath("$.roles[0]").value("USER"));
  }

  /**
   * ✅ The admin account authenticates and carries the ADMIN role.
   */
  @Test
  void testLoginAsAdminCarriesAdminRole() throws Exception {
    mockMvc.perform(post("/api/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"username\":\"admin\",\"password\":\"admin\"}"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.roles[0]").value("ADMIN"));
  }

  /**
   * ✅ Wrong credentials are 401, and the response does not reveal which field was wrong.
   */
  @Test
  void testLoginWithBadCredentialsIsUnauthorized() throws Exception {
    mockMvc.perform(post("/api/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"username\":\"jason\",\"password\":\"wrong\"}"))
      .andExpect(status().isUnauthorized())
      .andExpect(jsonPath("$.success").value(false))
      .andExpect(jsonPath("$.message").value("Invalid username or password."));
  }
}
