package com.message.inspector.message_inspector.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class JolokiaReadResponse {

  @JsonProperty("value")
  private Map<String, QueueDetails> value;

  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class QueueDetails {

    @JsonProperty("Name")
    private String name;

    @JsonProperty("MessageCount")
    private int messageCount;
  }
}
