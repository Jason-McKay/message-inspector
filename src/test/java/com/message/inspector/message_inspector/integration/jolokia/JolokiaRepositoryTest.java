package com.message.inspector.message_inspector.integration.jolokia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.message.inspector.message_inspector.dto.JolokiaResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

import java.util.List;

/**
 * Characterisation tests for {@link JolokiaRepository}.
 *
 * <p>These pin down what the class does <em>today</em>. They were written ahead of the move
 * to this {@code integration/jolokia} package and carried through it unchanged, which is
 * what makes that move verifiable. Only {@code fetchQueueNamesWithJolokia} remains -- the
 * dead {@code fetchQueuesWithJolokia} path (and its {@code JolokiaReadResponse} DTO) was
 * deleted along with the two dead discovery endpoints it served.
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
  // fetchQueueNamesWithJolokia
  // ---------------------------------------------------------------------

  @Test
  @DisplayName("fetchQueueNamesWithJolokia unwraps the envelope and returns the names")
  void fetchQueueNamesWithJolokia_returnsResponse() {
    JolokiaResponse<List<String>> body = new JolokiaResponse<>();
    body.setValue(List.of("DLQ", "ExpiryQueue"));
    body.setStatus(200);

    when(jolokiaRestTemplate.exchange(
      any(String.class), eq(HttpMethod.GET), eq(null), any(ParameterizedTypeReference.class)))
      .thenReturn(ResponseEntity.ok(body));

    // The JMX envelope stays inside the repository: callers get the plain list.
    assertThat(jolokiaRepository.fetchQueueNamesWithJolokia())
      .containsExactly("DLQ", "ExpiryQueue");
  }

  // The exception type matters, not just the message: ApiExceptionHandler maps
  // JolokiaAccessException to 502, so a bare RuntimeException here would surface as 500.

  @Test
  @DisplayName("fetchQueueNamesWithJolokia wraps a missing value section in a JolokiaAccessException")
  void fetchQueueNamesWithJolokia_missingValue_throws() {
    JolokiaResponse<List<String>> body = new JolokiaResponse<>();
    body.setValue(null);

    when(jolokiaRestTemplate.exchange(
      any(String.class), eq(HttpMethod.GET), eq(null), any(ParameterizedTypeReference.class)))
      .thenReturn(ResponseEntity.ok(body));

    assertThatThrownBy(() -> jolokiaRepository.fetchQueueNamesWithJolokia())
      .isInstanceOf(JolokiaAccessException.class)
      .hasMessageContaining("Response is null or missing value section");
  }

  @Test
  @DisplayName("fetchQueueNamesWithJolokia wraps HTTP errors in a JolokiaAccessException")
  void fetchQueueNamesWithJolokia_httpError_throws() {
    when(jolokiaRestTemplate.exchange(
      any(String.class), eq(HttpMethod.GET), eq(null), any(ParameterizedTypeReference.class)))
      .thenThrow(HttpClientErrorException.create(
        HttpStatus.NOT_FOUND, "Not Found", null, null, null));

    assertThatThrownBy(() -> jolokiaRepository.fetchQueueNamesWithJolokia())
      .isInstanceOf(JolokiaAccessException.class)
      .hasMessageContaining("Failed to fetch queue details");
  }

  @Test
  @DisplayName("fetchQueueNamesWithJolokia wraps transport failures in a JolokiaAccessException")
  void fetchQueueNamesWithJolokia_transportFailure_throws() {
    when(jolokiaRestTemplate.exchange(
      any(String.class), eq(HttpMethod.GET), eq(null), any(ParameterizedTypeReference.class)))
      .thenThrow(new ResourceAccessException("broker unreachable"));

    assertThatThrownBy(() -> jolokiaRepository.fetchQueueNamesWithJolokia())
      .isInstanceOf(JolokiaAccessException.class)
      .hasMessageContaining("Failed to fetch queue details");
  }
}
