package com.message.inspector.message_inspector.security;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Characterises the CORS contract the Angular dev server relies on.
 * <p>
 * CORS was previously handled by a CorsFilter registered manually at HIGHEST_PRECEDENCE,
 * ahead of the security chain. It is now wired through {@code http.cors(...)} instead.
 * These tests pin the observable behaviour so that swap stays behaviour-preserving.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityConfigTest {

  public static final String ALLOWED_ORIGIN = "http://localhost:4200";
  public static final String DISALLOWED_ORIGIN = "http://evil.example.com";
  public static final String ANY_API_PATH = "/api/queues/artemis-straight";

  @Autowired
  private MockMvc mockMvc;

  /**
   * ✅ Preflight from the Angular dev server is answered, with credentials allowed.
   */
  @Test
  void testPreflightFromAllowedOriginSucceeds() throws Exception {
    mockMvc.perform(options(ANY_API_PATH)
        .header("Origin", ALLOWED_ORIGIN)
        .header("Access-Control-Request-Method", "GET"))
      .andExpect(status().isOk())
      .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN))
      .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
  }

  /**
   * ✅ Preflight is answered without authentication -- the point of wiring CORS into
   * the security chain rather than bolting it on in front.
   */
  @Test
  void testPreflightForDestructiveEndpointSucceeds() throws Exception {
    mockMvc.perform(options("/api/messages/queue/testQueue/message/ID:abc")
        .header("Origin", ALLOWED_ORIGIN)
        .header("Access-Control-Request-Method", "DELETE"))
      .andExpect(status().isOk())
      .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN));
  }

  /**
   * ❌ An unlisted origin is rejected rather than echoed back.
   */
  @Test
  void testPreflightFromDisallowedOriginIsRejected() throws Exception {
    mockMvc.perform(options(ANY_API_PATH)
        .header("Origin", DISALLOWED_ORIGIN)
        .header("Access-Control-Request-Method", "GET"))
      .andExpect(status().isForbidden())
      .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
  }

  /**
   * ✅ The API is reachable unauthenticated. This pins the CURRENT deliberate state --
   * auth arrives in Phase 4, at which point this expectation should change.
   * <p>
   * Asserts only that the request is not rejected by security; the endpoint itself may
   * fail for want of a live broker, which is not what this test is about.
   */
  @Test
  void testApiIsCurrentlyPermittedWithoutAuthentication() throws Exception {
    mockMvc.perform(get("/api/queues/artemis-jolokia/names"))
      .andExpect(result -> {
        int status = result.getResponse().getStatus();
        assertNotEquals(401, status, "endpoint should not require authentication yet");
        assertNotEquals(403, status, "endpoint should not be forbidden yet");
      });
  }
}
