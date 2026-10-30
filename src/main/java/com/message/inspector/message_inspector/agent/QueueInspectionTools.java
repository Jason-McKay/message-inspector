package com.message.inspector.message_inspector.agent;

import com.message.inspector.message_inspector.dto.AgentMessageDto;
import com.message.inspector.message_inspector.dto.AgentMessagePageDto;
import com.message.inspector.message_inspector.dto.JmsMessageDto;
import com.message.inspector.message_inspector.integration.jolokia.JolokiaAccessException;
import com.message.inspector.message_inspector.service.MessageService;
import com.message.inspector.message_inspector.service.QueueService;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import jakarta.jms.JMSException;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.jms.JmsException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * Read-only MCP tools: list queues, browse a queue, fetch one message. None of them consumes
 * or changes anything, so any authenticated user may call them, as with the REST GETs.
 * <p>
 * The limits are constants concatenated into the tool descriptions, so the limit an agent is
 * told about and the one enforced here cannot drift apart.
 */
@Component
@RequiredArgsConstructor
public class QueueInspectionTools {

  static final int DEFAULT_PAGE_SIZE = 20;
  static final int MAX_PAGE_SIZE = 100;
  /** Payload characters per message in a browse page. */
  static final int PREVIEW_PAYLOAD_CHARS = 500;
  /** Payload characters for a single message from get_message. */
  static final int FULL_PAYLOAD_CHARS = 8000;

  private final QueueService queueService;
  private final MessageService messageService;
  private final JsonMapper jsonMapper;

  @McpTool(
    name = "list_queues",
    title = "List queues",
    description = "List the names of all queues on the ActiveMQ Artemis broker. Call this first: every other "
      + "tool takes a queue name, and refuses any name that is not in this list. Read-only.",
    annotations = @McpTool.McpAnnotations(
      readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
  public CallToolResult listQueues() {
    try {
      return ToolResults.json(jsonMapper, queueService.fetchQueueNamesWithJolokia());
    } catch (JMSException | JmsException | JolokiaAccessException e) {
      System.out.println("list_queues: broker error: " + ToolAudit.quote(e.getMessage()));
      return ToolResults.error(ToolResults.BROKER_UNAVAILABLE);
    }
  }

  @McpTool(
    name = "browse_messages",
    title = "Browse messages",
    description = "Browse one page of the messages on a queue, in queue order, without consuming them. Each "
      + "message has its messageId, timestamp (epoch millis), properties and a payload preview of at most "
      + PREVIEW_PAYLOAD_CHARS + " characters; payloadTruncated and payloadLength say whether it was cut and "
      + "from how long. Call get_message for up to " + FULL_PAYLOAD_CHARS + " characters of one payload. "
      + "Page with offset while hasMore is true. Default page size " + DEFAULT_PAGE_SIZE + ", maximum "
      + MAX_PAGE_SIZE + "; larger values are clamped and the page's limit field shows the one applied. "
      + "Only text messages can be read. Read-only.",
    annotations = @McpTool.McpAnnotations(
      readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
  public CallToolResult browseMessages(
    @McpToolParam(description = "Exact queue name, as returned by list_queues.", required = true)
    String queueName,
    @McpToolParam(description = "Messages per page, 1 to " + MAX_PAGE_SIZE + ". Default " + DEFAULT_PAGE_SIZE + ".",
      required = false)
    Integer limit,
    @McpToolParam(description = "Number of messages to skip, from 0. Default 0.", required = false)
    Integer offset) {
    try {
      if (!isKnownQueue(queueName)) {
        return ToolResults.error(ToolResults.UNKNOWN_QUEUE);
      }
      List<JmsMessageDto> messages = messageService.getAllMessages(queueName);
      return ToolResults.json(jsonMapper, page(queueName, messages, limit, offset));
    } catch (IllegalArgumentException e) {
      // On browse this can only be convertToDto meeting a non-text message.
      System.out.println("browse_messages: rejected: " + ToolAudit.quote(e.getMessage()));
      return ToolResults.error(ToolResults.UNREADABLE_QUEUE);
    } catch (JMSException | JmsException | JolokiaAccessException e) {
      System.out.println("browse_messages: broker error: " + ToolAudit.quote(e.getMessage()));
      return ToolResults.error(ToolResults.BROKER_UNAVAILABLE);
    }
  }

  @McpTool(
    name = "get_message",
    title = "Get message",
    description = "Fetch one message by its messageId, as returned by browse_messages, without consuming it. "
      + "The payload is cut to at most " + FULL_PAYLOAD_CHARS + " characters; payloadTruncated and "
      + "payloadLength say whether it was. If no message on the queue has that ID (it may have been consumed "
      + "since you browsed) the result says so and is not an error. Read-only.",
    annotations = @McpTool.McpAnnotations(
      readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
  public CallToolResult getMessage(
    @McpToolParam(description = "Exact queue name, as returned by list_queues.", required = true)
    String queueName,
    @McpToolParam(description = "The messageId exactly as browse_messages returned it, e.g. ID:1a2b-....",
      required = true)
    String messageId) {
    try {
      if (!isKnownQueue(queueName)) {
        return ToolResults.error(ToolResults.UNKNOWN_QUEUE);
      }
      JmsMessageDto message = messageService.getMessageById(queueName, messageId);
      if (message == null) {
        return ToolResults.text("No message with that ID is on the queue. Call browse_messages for current IDs.");
      }
      return ToolResults.json(jsonMapper, toAgentDto(message, FULL_PAYLOAD_CHARS));
    } catch (IllegalArgumentException e) {
      System.out.println("get_message: rejected: " + ToolAudit.quote(e.getMessage()));
      return ToolResults.error(ToolResults.INVALID_MESSAGE_ID);
    } catch (JMSException | JmsException | JolokiaAccessException e) {
      System.out.println("get_message: broker error: " + ToolAudit.quote(e.getMessage()));
      return ToolResults.error(ToolResults.BROKER_UNAVAILABLE);
    }
  }

  private boolean isKnownQueue(String queueName) throws JMSException {
    return queueName != null && queueService.fetchQueueNamesWithJolokia().contains(queueName);
  }

  /**
   * Cut a page out of the full list. The repository browses the whole queue regardless, so
   * this bounds what the agent receives, not what the broker is asked for.
   */
  static AgentMessagePageDto page(String queueName, List<JmsMessageDto> messages, Integer limit, Integer offset) {
    int pageSize = limit == null ? DEFAULT_PAGE_SIZE : Math.clamp(limit, 1, MAX_PAGE_SIZE);
    int total = messages.size();
    int from = offset == null ? 0 : Math.clamp(offset, 0, total);
    int to = from + Math.min(pageSize, total - from);

    AgentMessagePageDto page = new AgentMessagePageDto();
    page.setQueueName(queueName);
    page.setOffset(from);
    page.setLimit(pageSize);
    page.setTotalMessages(total);
    page.setHasMore(to < total);
    page.setMessages(messages.subList(from, to).stream()
      .map(message -> toAgentDto(message, PREVIEW_PAYLOAD_CHARS))
      .toList());
    return page;
  }

  static AgentMessageDto toAgentDto(JmsMessageDto message, int maxPayloadChars) {
    String payload = message.getPayload();
    String cut = truncate(payload, maxPayloadChars);

    AgentMessageDto dto = new AgentMessageDto();
    dto.setMessageId(message.getMessageId());
    dto.setQueueName(message.getQueueName());
    dto.setTimestamp(message.getTimestamp());
    dto.setProperties(message.getProperties());
    dto.setPayload(cut);
    dto.setPayloadLength(payload == null ? 0 : payload.length());
    dto.setPayloadTruncated(payload != null && cut.length() < payload.length());
    return dto;
  }

  /**
   * At most {@code maxChars} UTF-16 chars of {@code text}, never ending on the first half of
   * a surrogate pair -- a lone surrogate is not valid Unicode and does not survive the
   * JSON encoding intact.
   */
  static String truncate(String text, int maxChars) {
    if (text == null || text.length() <= maxChars) {
      return text;
    }
    int end = Character.isHighSurrogate(text.charAt(maxChars - 1)) ? maxChars - 1 : maxChars;
    return text.substring(0, end);
  }
}
