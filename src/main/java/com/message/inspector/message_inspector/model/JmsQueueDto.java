package com.message.inspector.message_inspector.model;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class JmsQueueDto {

  private String queueId;
  private String queueName;
  private int messageCount;

  public JmsQueueDto() {}

  public JmsQueueDto(String queueId, String queueName, int messageCount) {
    this.queueId = queueId;
    this.queueName = queueName;
    this.messageCount = messageCount;
  }

}
