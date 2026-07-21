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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Characterises the HTTP contract of {@link QueueController}: the three queue-discovery
 * routes and the two last-used-queue preference routes.
 * <p>
 * Wiring safety net for the Phase 3 package refactor -- both collaborating services are
 * mocked, so this asserts routing and serialisation, not broker or Redis behaviour.
 * {@link SecurityConfig} is imported so the real filter chain applies.
 */
@WebMvcTest(QueueController.class)
@Import(SecurityConfig.class)
class QueueControllerTest {

  public static final String USER_ID = "jason";
  public static final String QUEUE_NAME = "test.queue.1";

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private QueueService queueService;

  @MockitoBean
  private QueuePreferenceService queuePreferenceService;

  /**
   * ✅ GET /api/queues/artemis-straight returns the queue list.
   * <p>
   * Note this endpoint is a stub in production -- JmsQueueRepository returns hardcoded
   * placeholders -- but the route itself is real and the UI calls it.
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
   * ✅ GET /api/queues/artemis-jolokia returns the queue list from the Jolokia path --
   * the working queue-discovery route.
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
   * ✅ POST /api/queues/last-used/{userId} stores the raw request body and returns an
   * empty 200.
   */
  @Test
  void testSaveLastUsedQueue() throws Exception {
    mockMvc.perform(post("/api/queues/last-used/" + USER_ID)
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
    mockMvc.perform(post("/api/queues/last-used/" + USER_ID)
        .contentType(MediaType.APPLICATION_JSON)
        .content("\"" + QUEUE_NAME + "\""))
      .andExpect(status().isOk());

    verify(queuePreferenceService, times(1)).saveLastUsedQueue(USER_ID, "\"" + QUEUE_NAME + "\"");
  }

  /**
   * ✅ GET /api/queues/last-used/{userId} returns the stored queue name.
   */
  @Test
  void testGetLastUsedQueue() throws Exception {
    when(queuePreferenceService.getLastUsedQueue(USER_ID)).thenReturn(QUEUE_NAME);

    mockMvc.perform(get("/api/queues/last-used/" + USER_ID))
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
    when(queuePreferenceService.getLastUsedQueue("unknown-user")).thenReturn(null);

    mockMvc.perform(get("/api/queues/last-used/unknown-user"))
      .andExpect(status().isOk())
      .andExpect(content().string(""));
  }
}
