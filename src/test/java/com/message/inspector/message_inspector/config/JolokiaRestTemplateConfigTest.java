package com.message.inspector.message_inspector.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.message.inspector.message_inspector.dto.JolokiaResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/**
 * Pins the Jolokia client's text/plain JSON handling. The broker's Jolokia bridge
 * returns JSON labelled as {@code text/plain}; a default Jackson converter will not
 * read that media type. Boot 4 replaced {@code MappingJackson2HttpMessageConverter}
 * with {@code JacksonJsonHttpMessageConverter}, and this is the check that the
 * replacement still deserialises the envelope.
 */
class JolokiaRestTemplateConfigTest {

  @Test
  void readsJolokiaJsonServedAsTextPlain() {
    RestTemplateConfig config = new RestTemplateConfig();
    ReflectionTestUtils.setField(config, "username", "admin");
    ReflectionTestUtils.setField(config, "password", "admin");
    RestTemplate restTemplate = config.jolokiaRestTemplate();

    MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
    server.expect(requestTo("http://jolokia.example/read"))
      .andRespond(withSuccess(
        "{\"request\":{\"type\":\"read\",\"mbean\":\"broker\",\"attribute\":\"AddressNames\"},"
          + "\"value\":[\"DLQ\"],\"timestamp\":1,\"status\":200}",
        MediaType.TEXT_PLAIN));

    ResponseEntity<JolokiaResponse<List<String>>> response = restTemplate.exchange(
      "http://jolokia.example/read",
      HttpMethod.GET,
      null,
      new ParameterizedTypeReference<>() {});

    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().getValue()).containsExactly("DLQ");
    server.verify();
  }
}
