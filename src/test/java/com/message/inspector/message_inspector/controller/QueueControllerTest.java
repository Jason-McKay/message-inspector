package com.message.inspector.message_inspector.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.message.inspector.message_inspector.integration.jolokia.JolokiaAccessException;
import com.message.inspector.message_inspector.security.SecurityConfig;
import com.message.inspector.message_inspector.service.QueuePreferenceService;
import com.message.inspector.message_inspector.service.QueueService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Characterises the HTTP contract of {@link QueueController}: the queue-names discovery
 * route and the two last-used-queue preference routes. (The two dead discovery routes,
 * {@code artemis-straight} and {@code artemis-jolokia}, were deleted along with their
 * backing code -- only {@code artemis-jolokia/names} ever worked.)
 * <p>
 * Wiring safety net for the Phase 3 package refactor -- both collaborating services are
 * mocked, so this asserts routing and serialisation, not broker or Redis behaviour.
 * {@link SecurityConfig} is imported so the real filter chain applies: every route now
 * requires a bearer token, so the class authenticates as a plain USER (which is all the
 * queue endpoints need) and the {@code JwtDecoder} is mocked so the chain can build. The
 * last-used routes now read the username from the principal, so {@code USER_ID} is also the
 * mock user's name.
 */
@WebMvcTest(QueueController.class)
@Import(SecurityConfig.class)
@WithMockUser(username = "jason", roles = "USER")
class QueueControllerTest {

  public static final String USER_ID = "jason";
  public static final String QUEUE_NAME = "test.queue.1";

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private QueueService queueService;

  @MockitoBean
  private QueuePreferenceService queuePreferenceService;

  @MockitoBean
  private JwtDecoder jwtDecoder;

  /**
   * ✅ GET /api/queues/artemis-jolokia/names returns a bare JSON array of names. The
   * Jolokia JMX envelope ({@code {request, value, timestamp, status}}) used to pass
   * through intact and is now unwrapped in the repository -- clients never see it.
   */
  @Test
  void testFetchQueueNamesWithJolokia() throws Exception {
    when(queueService.fetchQueueNamesWithJolokia())
      .thenReturn(List.of("DLQ", "ExpiryQueue", QUEUE_NAME));

    mockMvc.perform(get("/api/queues/artemis-jolokia/names"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$", hasSize(3)))
      .andExpect(jsonPath("$[0]").value("DLQ"));

    verify(queueService, times(1)).fetchQueueNamesWithJolokia();
  }

  /**
   * ✅ A Jolokia failure surfaces as 502 with the shared failure body -- an upstream
   * fault, not a 500. {@code JolokiaAccessException} propagates through the pass-through
   * service into {@code ApiExceptionHandler}, mirroring the JMSException mapping.
   */
  @Test
  void testFetchQueueNamesWithJolokia_JolokiaFailureIs502() throws Exception {
    when(queueService.fetchQueueNamesWithJolokia())
      .thenThrow(new JolokiaAccessException("Failed to fetch queue details: connection refused"));

    mockMvc.perform(get("/api/queues/artemis-jolokia/names"))
      .andExpect(status().isBadGateway())
      .andExpect(jsonPath("$.success").value(false))
      .andExpect(jsonPath("$.message")
        .value("Broker unavailable: Failed to fetch queue details: connection refused"));
  }

  /**
   * ✅ POST /api/queues/last-used stores the raw request body under the authenticated
   * user's name (from the principal, not the URL) and returns an empty 200.
   */
  @Test
  void testSaveLastUsedQueue() throws Exception {
    mockMvc.perform(post("/api/queues/last-used")
        .contentType(MediaType.TEXT_PLAIN)
        .content(QUEUE_NAME))
      .andExpect(status().isOk())
      .andExpect(content().string(""));

    verify(queuePreferenceService, times(1)).saveLastUsedQueue(USER_ID, QUEUE_NAME);
  }

  /**
   * ⚠️ The body binds as a raw String, so a JSON-quoted body is stored verbatim,
   * quotes included -- there is no unmarshalling. Pinned because it makes the endpoint
   * content-type-sensitive in a way the signature does not suggest.
   */
  @Test
  void testSaveLastUsedQueue_JsonBodyIsStoredVerbatimIncludingQuotes() throws Exception {
    mockMvc.perform(post("/api/queues/last-used")
        .contentType(MediaType.APPLICATION_JSON)
        .content("\"" + QUEUE_NAME + "\""))
      .andExpect(status().isOk());

    verify(queuePreferenceService, times(1)).saveLastUsedQueue(USER_ID, "\"" + QUEUE_NAME + "\"");
  }

  /**
   * ✅ GET /api/queues/last-used returns the authenticated user's stored queue name.
   */
  @Test
  void testGetLastUsedQueue() throws Exception {
    when(queuePreferenceService.getLastUsedQueue(USER_ID)).thenReturn(QUEUE_NAME);

    mockMvc.perform(get("/api/queues/last-used"))
      .andExpect(status().isOk())
      .andExpect(content().string(QUEUE_NAME));

    verify(queuePreferenceService, times(1)).getLastUsedQueue(USER_ID);
  }

  /**
   * ⚠️ A user with no stored preference gets a 200 with an empty body rather than a 404
   * or a JSON null. The Angular client treats the empty string as "no preference".
   */
  @Test
  void testGetLastUsedQueue_NoPreferenceReturnsOkWithEmptyBody() throws Exception {
    when(queuePreferenceService.getLastUsedQueue(USER_ID)).thenReturn(null);

    mockMvc.perform(get("/api/queues/last-used"))
      .andExpect(status().isOk())
      .andExpect(content().string(""));
  }

  /**
   * ✅ Queue discovery is closed to anonymous callers now — 401 without a token, before
   * the service is reached.
   */
  @Test
  @WithAnonymousUser
  void testDiscovery_UnauthenticatedIsUnauthorized() throws Exception {
    mockMvc.perform(get("/api/queues/artemis-jolokia/names"))
      .andExpect(status().isUnauthorized());

    verify(queueService, times(0)).fetchQueueNamesWithJolokia();
  }
}
