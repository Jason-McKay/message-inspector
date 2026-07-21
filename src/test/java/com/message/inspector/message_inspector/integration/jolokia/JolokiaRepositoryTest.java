package com.message.inspector.message_inspector.integration.jolokia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.message.inspector.message_inspector.model.JmsQueueDto;
import com.message.inspector.message_inspector.model.JolokiaReadResponse;
import com.message.inspector.message_inspector.model.JolokiaResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Characterisation tests for {@link JolokiaRepository}.
 *
 * <p>These pin down what the class does <em>today</em>. They were written ahead of the move
 * to this {@code integration/jolokia} package and carried through it unchanged, which is
 * what makes that move verifiable. They deliberately document current behaviour rather
 * than ideal behaviour — including the broad exception swallowing in
 * {@code fetchQueuesWithJolokia}, which turns any failure into an empty list.
 */
class JolokiaRepositoryTest {

  private static final String JOLOKIA_URL = "http://localhost:8161/console/jolokia";
  private static final String BROKER_NAME = "0.0.0.0";

  @Mock
  private RestTemplate jolokiaRestTemplate;

  private JolokiaRepository jolokiaRepository;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    jolokiaRepository = new JolokiaRepository(jolokiaRestTemplate);
    ReflectionTestUtils.setField(jolokiaRepository, "jolokiaUrl", JOLOKIA_URL);
    ReflectionTestUtils.setField(jolokiaRepository, "brokerName", BROKER_NAME);
  }

  // ---------------------------------------------------------------------
  // fetchQueuesWithJolokia
  // ---------------------------------------------------------------------

  @Test
  @DisplayName("fetchQueuesWithJolokia maps each value entry to a JmsQueueDto")
  void fetchQueuesWithJolokia_mapsEntriesToDtos() {
    JolokiaReadResponse.QueueDetails orders = queueDetails("orders", 7);
    JolokiaReadResponse.QueueDetails dlq = queueDetails("DLQ", 0);

    Map<String, JolokiaReadResponse.QueueDetails> value = new LinkedHashMap<>();
    value.put("queue-1", orders);
    value.put("queue-2", dlq);

    JolokiaReadResponse body = new JolokiaReadResponse();
    body.setValue(value);

    when(jolokiaRestTemplate.exchange(any(String.class), eq(HttpMethod.GET), eq(null), eq(JolokiaReadResponse.class)))
      .thenReturn(ResponseEntity.ok(body));

    List<JmsQueueDto> queues = jolokiaRepository.fetchQueuesWithJolokia();

    assertThat(queues).hasSize(2);
    assertThat(queues.get(0).getQueueId()).isEqualTo("queue-1");
    assertThat(queues.get(0).getQueueName()).isEqualTo("orders");
    assertThat(queues.get(0).getMessageCount()).isEqualTo(7);
    assertThat(queues.get(1).getQueueId()).isEqualTo("queue-2");
    assertThat(queues.get(1).getQueueName()).isEqualTo("DLQ");
    assertThat(queues.get(1).getMessageCount()).isZero();
  }

  @Test
  @DisplayName("fetchQueuesWithJolokia targets the broker's AddressNames attribute")
  void fetchQueuesWithJolokia_buildsExpectedUrl() {
    JolokiaReadResponse body = new JolokiaReadResponse();
    body.setValue(new LinkedHashMap<>());

    when(jolokiaRestTemplate.exchange(any(String.class), eq(HttpMethod.GET), eq(null), eq(JolokiaReadResponse.class)))
      .thenReturn(ResponseEntity.ok(body));

    jolokiaRepository.fetchQueuesWithJolokia();

    ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
    org.mockito.Mockito.verify(jolokiaRestTemplate)
      .exchange(url.capture(), eq(HttpMethod.GET), eq(null), eq(JolokiaReadResponse.class));

    assertThat(url.getValue())
      .isEqualTo(JOLOKIA_URL + "/read/org.apache.activemq.artemis:broker=\"" + BROKER_NAME + "\"/AddressNames");
  }

  @Test
  @DisplayName("fetchQueuesWithJolokia returns an empty list when the body is null (NPE is swallowed)")
  void fetchQueuesWithJolokia_nullBody_returnsEmptyList() {
    when(jolokiaRestTemplate.exchange(any(String.class), eq(HttpMethod.GET), eq(null), eq(JolokiaReadResponse.class)))
      .thenReturn(ResponseEntity.ok(null));

    assertThat(jolokiaRepository.fetchQueuesWithJolokia()).isEmpty();
  }

  @Test
  @DisplayName("fetchQueuesWithJolokia returns an empty list when the transport fails")
  void fetchQueuesWithJolokia_transportFailure_returnsEmptyList() {
    when(jolokiaRestTemplate.exchange(any(String.class), eq(HttpMethod.GET), eq(null), eq(JolokiaReadResponse.class)))
      .thenThrow(new ResourceAccessException("broker unreachable"));

    assertThat(jolokiaRepository.fetchQueuesWithJolokia()).isEmpty();
  }

  // ---------------------------------------------------------------------
  // fetchQueueNamesWithJolokia
  // ---------------------------------------------------------------------

  @Test
  @DisplayName("fetchQueueNamesWithJolokia returns the response when it carries a value")
  void fetchQueueNamesWithJolokia_returnsResponse() {
    JolokiaResponse<List<String>> body = new JolokiaResponse<>();
    body.setValue(List.of("DLQ", "ExpiryQueue"));
    body.setStatus(200);

    when(jolokiaRestTemplate.exchange(
      any(String.class), eq(HttpMethod.GET), eq(null), any(ParameterizedTypeReference.class)))
      .thenReturn(ResponseEntity.ok(body));

    JolokiaResponse<List<String>> response = jolokiaRepository.fetchQueueNamesWithJolokia();

    assertThat(response.getValue()).containsExactly("DLQ", "ExpiryQueue");
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  @DisplayName("fetchQueueNamesWithJolokia wraps a missing value section in a RuntimeException")
  void fetchQueueNamesWithJolokia_missingValue_throws() {
    JolokiaResponse<List<String>> body = new JolokiaResponse<>();
    body.setValue(null);

    when(jolokiaRestTemplate.exchange(
      any(String.class), eq(HttpMethod.GET), eq(null), any(ParameterizedTypeReference.class)))
      .thenReturn(ResponseEntity.ok(body));

    assertThatThrownBy(() -> jolokiaRepository.fetchQueueNamesWithJolokia())
      .isInstanceOf(RuntimeException.class)
      .hasMessageContaining("Response is null or missing value section");
  }

  @Test
  @DisplayName("fetchQueueNamesWithJolokia wraps HTTP errors in a RuntimeException")
  void fetchQueueNamesWithJolokia_httpError_throws() {
    when(jolokiaRestTemplate.exchange(
      any(String.class), eq(HttpMethod.GET), eq(null), any(ParameterizedTypeReference.class)))
      .thenThrow(HttpClientErrorException.create(
        HttpStatus.NOT_FOUND, "Not Found", null, null, null));

    assertThatThrownBy(() -> jolokiaRepository.fetchQueueNamesWithJolokia())
      .isInstanceOf(RuntimeException.class)
      .hasMessageContaining("Failed to fetch queue details");
  }

  @Test
  @DisplayName("fetchQueueNamesWithJolokia wraps transport failures in a RuntimeException")
  void fetchQueueNamesWithJolokia_transportFailure_throws() {
    when(jolokiaRestTemplate.exchange(
      any(String.class), eq(HttpMethod.GET), eq(null), any(ParameterizedTypeReference.class)))
      .thenThrow(new ResourceAccessException("broker unreachable"));

    assertThatThrownBy(() -> jolokiaRepository.fetchQueueNamesWithJolokia())
      .isInstanceOf(RuntimeException.class)
      .hasMessageContaining("Failed to fetch queue details");
  }

  private static JolokiaReadResponse.QueueDetails queueDetails(String name, int messageCount) {
    JolokiaReadResponse.QueueDetails details = new JolokiaReadResponse.QueueDetails();
    details.setName(name);
    details.setMessageCount(messageCount);
    return details;
  }
}
