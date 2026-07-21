package com.message.inspector.message_inspector.integration.jolokia;

import com.message.inspector.message_inspector.dto.JmsQueueDto;
import com.message.inspector.message_inspector.dto.JolokiaReadResponse;
import com.message.inspector.message_inspector.dto.JolokiaResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Repository;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Repository
@RequiredArgsConstructor
public class JolokiaRepository {

  private final RestTemplate jolokiaRestTemplate;
  @Value("${jolokia.url}") String jolokiaUrl;
  @Value("${jolokia.brokerName}") private String brokerName;

  public List<com.message.inspector.message_inspector.dto.JmsQueueDto> fetchQueuesWithJolokia() {
    List<JmsQueueDto> queueList = new ArrayList<>();

    try {
      String url = String.format("%s/read/org.apache.activemq.artemis:broker=\"%s\"/AddressNames",
        jolokiaUrl, brokerName);

      HttpHeaders headers = new HttpHeaders();
      headers.setAccept(List.of(MediaType.APPLICATION_JSON));

      ResponseEntity<JolokiaReadResponse> responseEntity = jolokiaRestTemplate.exchange(
        url,
        HttpMethod.GET,
        null,
        JolokiaReadResponse.class
      );

      // print out the response
      logResponse("", responseEntity.getStatusCode().value(), responseEntity.getHeaders(), responseEntity.getBody().toString(), responseEntity.getBody() != null, responseEntity.getBody().getValue().toString());

      JolokiaReadResponse response = responseEntity.getBody();
      if (response == null || response.getValue() == null) {
        return List.of();
      }

      for (Map.Entry<String, JolokiaReadResponse.QueueDetails> entry : response.getValue().entrySet()) {
        String queueId = entry.getKey();
        JolokiaReadResponse.QueueDetails details = entry.getValue();
        queueList.add(new JmsQueueDto(queueId, details.getName(), details.getMessageCount()));
      }

    } catch (Exception e) {
      System.out.println("Failed to fetch queue list from Jolokia\n" + e.getMessage());
    }

    return queueList;
  }

  public JolokiaResponse<List<String>> fetchQueueNamesWithJolokia() {
//    String url = String.format("%s/read/org.apache.activemq.artemis:broker=\"%s\",component=addresses,address=\"%s\"",
//      jolokiaUrl, brokerName, queueName);
    String url = String.format("%s/read/org.apache.activemq.artemis:broker=\"%s\"/AddressNames",
      jolokiaUrl, brokerName);

    try {
      ResponseEntity<JolokiaResponse<List<String>>> responseEntity = jolokiaRestTemplate.exchange(
        url,
        HttpMethod.GET,
        null,
        getParameterizedTypeReference());

      logResponse("fetchQueueNamesWithJolokia", responseEntity);

      JolokiaResponse<List<String>> response = responseEntity.getBody();

      if (response != null && response.getValue() != null) {
        return response;
      } else {
        throw new RuntimeException("Response is null or missing value section");
      }
    } catch (HttpStatusCodeException e) {
      // Handle HTTP errors (e.g., 404, 500)
      System.err.println("HTTP Error: " + e.getStatusCode() + " - " + e.getResponseBodyAsString());
      throw new RuntimeException("Failed to fetch queue details: " + e.getMessage(), e);
    } catch (RestClientException e) {
      // Handle other client errors
      System.err.println("Client Error: " + e.getMessage());
      throw new RuntimeException("Failed to fetch queue details: " + e.getMessage(), e);
    } catch (Exception e) {
      // Handle unexpected errors
      System.err.println("Unexpected Error: " + e.getMessage());
      throw new RuntimeException("An unexpected error occurred: " + e.getMessage(), e);
    }
  }

  private ParameterizedTypeReference<JolokiaResponse<List<String>>> getParameterizedTypeReference() {
    return new ParameterizedTypeReference<>() {};
  }

  private void logResponse(String responseType, int statusCode, HttpHeaders headers, String body, boolean isBody, String bodyPayload) {
    System.out.println("Response Type: " + responseType);
    System.out.println("Response Status Code: " + statusCode);
    System.out.println("Response Headers: " + headers);
    System.out.println("Response Body: " + body);
    assert isBody;
    System.out.println("Response Body (value):  " + bodyPayload);
  }

  private void logResponse(String responseType, ResponseEntity<JolokiaResponse<List<String>>> responseEntity) {
    //int statusCode, HttpHeaders headers, String body, boolean isBody, String bodyPayload) {
    //responseEntity.getStatusCode().value(), responseEntity.getHeaders(), responseEntity.getBody().toString(), responseEntity.getBody() != null, responseEntity.getBody().getValue().toString()
    try {
      System.out.println("Response Type: " + responseType);
      System.out.println("Response Status Code: " + responseEntity.getStatusCode().value());
      System.out.println("Response Headers: " + responseEntity.getHeaders());
      System.out.println("Response Body: " + responseEntity.getBody());
      Object value = responseEntity.getBody() != null ? responseEntity.getBody().getValue() : null;
      System.out.println("Response Body (value):  " + (value != null ? value.toString() : "null"));

    } catch (Exception e) {
      System.out.println("Failed to log error: " + e.getMessage());
    }
  }
}
