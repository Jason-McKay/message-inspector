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

import com.message.inspector.message_inspector.dto.JmsQueueDto;
import com.message.inspector.message_inspector.dto.JolokiaResponse;
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
 * Characterises the HTTP contract of {@link QueueController}: the three queue-discovery
 * routes and the two last-used-queue preference routes.
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
   * ✅ GET /api/queues/artemis-straight returns the queue list.
   * <p>
   * Note this endpoint is dead in production: JmsQueueRepository calls
   * {@code session.createBrowser(null)}, which always throws, and the catch swallows it
   * into an empty list -- so the hardcoded placeholder queues below the throw are never
   * reached and the endpoint returns {@code []}. Verified against a live broker
   * 2026-07-21. The route and its serialisation are still real, which is what this test
   * covers; the service is mocked, so the live failure is invisible here.
   */
  @Test
  void testFetchQueuesWithArtemis() throws Exception {
    when(queueService.fetchQueuesWithArtemis())
      .thenReturn(List.of(new JmsQueueDto("1", QUEUE_NAME, 10)));

    mockMvc.perform(get("/api/queues/artemis-straight"))
      .andExpect(status().isOk())
      .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
      .andExpect(jsonPath("$", hasSize(1)))
      .andExpect(jsonPath("$[0].queueId").value("1"))
      .andExpect(jsonPath("$[0].queueName").value(QUEUE_NAME))
      .andExpect(jsonPath("$[0].messageCount").value(10));

    verify(queueService, times(1)).fetchQueuesWithArtemis();
  }

  /**
   * ✅ GET /api/queues/artemis-jolokia returns the queue list from the Jolokia path.
   * <p>
   * Like {@code artemis-straight}, this route is dead in production: it returns {@code []}
   * because the Jolokia response comes back as {@code text/plain} and no converter binds it
   * to {@code JolokiaReadResponse}, and the broad catch swallows the failure. Only
   * {@code artemis-jolokia/names} actually works. The service is mocked here, so the route
   * and its serialisation are what this covers -- the live failure is invisible.
   */
  @Test
  void testFetchQueuesWithJolokia() throws Exception {
    when(queueService.fetchQueuesWithJolokia())
      .thenReturn(List.of(new JmsQueueDto("1", "DLQ", 3),
                          new JmsQueueDto("2", "ExpiryQueue", 0)));

    mockMvc.perform(get("/api/queues/artemis-jolokia"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$", hasSize(2)))
      .andExpect(jsonPath("$[0].queueName").value("DLQ"))
      .andExpect(jsonPath("$[1].queueName").value("ExpiryQueue"));

    verify(queueService, times(1)).fetchQueuesWithJolokia();
  }

  /**
   * ✅ GET /api/queues/artemis-jolokia/names passes the Jolokia envelope through intact,
   * including the nested status/value fields the UI reads.
   */
  @Test
  void testFetchQueueNamesWithJolokia() throws Exception {
    JolokiaResponse<List<String>> response = new JolokiaResponse<>();
    response.setStatus(200);
    response.setTimestamp(1_753_000_000L);
    response.setValue(List.of("DLQ", "ExpiryQueue", QUEUE_NAME));
    when(queueService.fetchQueueNamesWithJolokia()).thenReturn(response);

    mockMvc.perform(get("/api/queues/artemis-jolokia/names"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.status").value(200))
      .andExpect(jsonPath("$.timestamp").value(1_753_000_000L))
      .andExpect(jsonPath("$.value", hasSize(3)))
      .andExpect(jsonPath("$.value[0]").value("DLQ"));

    verify(queueService, times(1)).fetchQueueNamesWithJolokia();
  }

  /**
   * ✅ The /names route does not collide with the /artemis-jolokia route above it.
   * Pinned because the two differ only by suffix, and a botched @RequestMapping during
   * the refactor would silently route one onto the other.
   */
  @Test
  void testJolokiaRoutesDoNotCollide() throws Exception {
    when(queueService.fetchQueuesWithJolokia()).thenReturn(List.of());

    mockMvc.perform(get("/api/queues/artemis-jolokia"))
      .andExpect(status().isOk());

    verify(queueService, times(1)).fetchQueuesWithJolokia();
    verify(queueService, times(0)).fetchQueueNamesWithJolokia();
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
