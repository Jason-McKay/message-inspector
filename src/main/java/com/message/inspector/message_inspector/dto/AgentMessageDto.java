package com.message.inspector.message_inspector.dto;

import lombok.Data;

import java.util.Map;

/**
 * A message as the MCP tools hand it to an AI agent: the fields of {@link JmsMessageDto},
 * with a payload that may have been cut short and two fields that say whether it was.
 * <p>
 * A separate type rather than new fields on {@code JmsMessageDto}, so the REST response
 * the Angular client binds to is untouched.
 */
@Data
public class AgentMessageDto {
  private String messageId;
  private String queueName;
  private String timestamp;
  private Map<String, String> properties;
  private String payload;
  /** Length of the full payload, before any truncation. */
  private int payloadLength;
  private boolean payloadTruncated;
}
