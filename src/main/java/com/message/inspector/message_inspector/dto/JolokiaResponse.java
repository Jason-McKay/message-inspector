package com.message.inspector.message_inspector.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class JolokiaResponse<T> {

  @JsonProperty("request")
  private RequestDetails request;

  @JsonProperty("value")
  private T value;

  @JsonProperty("timestamp")
  private long timestamp;

  @JsonProperty("status")
  private int status;

  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class RequestDetails {
    @JsonProperty("type")
    private String type;

    @JsonProperty("mbean")
    private String mbean;

    @JsonProperty("attribute")
    private String attribute;
  }
}
