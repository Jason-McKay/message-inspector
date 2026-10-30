package com.message.inspector.message_inspector.agent;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds the results the tools return, so success and failure look the same across them.
 * <p>
 * Tools return a {@link CallToolResult} rather than throwing, because the framework turns a
 * thrown exception into an error result carrying its message and its root cause's. Neither
 * is safe to hand to an agent here: {@code requireValidMessageId} quotes the rejected ID
 * back, and JMS exceptions describe the broker's internals. The texts below are the only
 * error wording an agent sees; the real exception goes to the server log.
 */
final class ToolResults {

  static final String UNKNOWN_QUEUE =
    "Unknown queue. Call list_queues and use one of the names it returns, exactly as written.";
  static final String INVALID_MESSAGE_ID =
    "The message ID is malformed, or the message is not a text message. Use a messageId exactly as browse_messages returned it.";
  static final String UNREADABLE_QUEUE =
    "The queue holds a message this server cannot read (only text messages are supported), so it cannot be browsed.";
  static final String BROKER_UNAVAILABLE =
    "Broker unavailable. Do not guess at the result; tell the user and try again later.";

  private ToolResults() {}

  static CallToolResult json(JsonMapper jsonMapper, Object value) {
    return json(jsonMapper, value, false);
  }

  static CallToolResult json(JsonMapper jsonMapper, Object value, boolean isError) {
    return CallToolResult.builder()
      .addTextContent(jsonMapper.writeValueAsString(value))
      .isError(isError)
      .build();
  }

  static CallToolResult text(String text) {
    return CallToolResult.builder()
      .addTextContent(text)
      .isError(false)
      .build();
  }

  static CallToolResult error(String text) {
    return CallToolResult.builder()
      .addTextContent(text)
      .isError(true)
      .build();
  }
}
