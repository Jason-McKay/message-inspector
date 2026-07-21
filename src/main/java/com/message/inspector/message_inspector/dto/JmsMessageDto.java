package com.message.inspector.message_inspector.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.Map;

@Setter
@Getter
public class JmsMessageDto {

  private String messageId;
  private String payload;
  private String queueName;
  private String timestamp;
  private Map<String, String> properties;

  public JmsMessageDto() {}

  public JmsMessageDto(String messageId, String payload, String queueName) {
    this.messageId = messageId;
    this.payload = payload;
    this.queueName = queueName;
  }

  public JmsMessageDto(String messageId, String payload, String queueName, String timestamp, Map<String, String> properties) {
    this.messageId = messageId;
    this.payload = payload;
    this.queueName = queueName;
    this.timestamp = timestamp;
    this.properties = properties;
  }

}
