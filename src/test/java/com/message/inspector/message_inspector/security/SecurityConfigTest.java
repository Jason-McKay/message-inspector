package com.message.inspector.message_inspector.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
  public static final String ANY_API_PATH = "/api/queues/artemis-jolokia/names";

  @Autowired
  private MockMvc mockMvc;

  /**
   * ✅ Preflight from the Angular dev server is answered for the allowed origin.
   * <p>
   * Credentials mode is now off: bearer auth rides in the Authorization header, not a
   * cookie, so {@code allowCredentials} was dropped to false and the
   * {@code Access-Control-Allow-Credentials} header is no longer emitted.
   */
  @Test
  void testPreflightFromAllowedOriginSucceeds() throws Exception {
    mockMvc.perform(options(ANY_API_PATH)
        .header("Origin", ALLOWED_ORIGIN)
        .header("Access-Control-Request-Method", "GET"))
      .andExpect(status().isOk())
      .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN))
      .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
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
   * ✅ The API now requires authentication: an unauthenticated request is rejected with
   * 401 by the security chain, before the controller (and any broker call) is reached.
   * This replaces the old "permitted without auth" characterisation — Phase 4 closed that
   * gap. Because security rejects first, this is broker-independent.
   */
  @Test
  void testApiRequiresAuthentication() throws Exception {
    mockMvc.perform(get("/api/queues/artemis-jolokia/names"))
      .andExpect(status().isUnauthorized());
  }
}
