package com.message.inspector.message_inspector.agent;

import com.message.inspector.message_inspector.dto.ApiResponse;
import com.message.inspector.message_inspector.integration.jolokia.JolokiaAccessException;
import com.message.inspector.message_inspector.service.MessageService;
import com.message.inspector.message_inspector.service.QueueService;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import jakarta.jms.JMSException;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.jms.JmsException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.function.Supplier;

/**
 * MCP tools that change the broker: resend, delete, move to DLQ. Every one is ADMIN-only and
 * every call is audited.
 * <p>
 * The role check is the same {@code @PreAuthorize} as on {@code MessageController}, and works
 * the same way: the MCP endpoint is behind the JWT filter chain and the tool runs on the
 * request thread, so the caller's token is in the security context when the method-security
 * proxy is consulted. A denied call never reaches the method, so its audit line comes from
 * {@link AgentAuthorizationAuditListener}; everything that does reach the method is audited
 * here, including calls refused before the broker is touched.
 * <p>
 * Each returns the service's {@link ApiResponse} unchanged, as JSON, with the tool result's
 * {@code isError} set from {@code success}. That keeps the REST endpoints' truthful-failure
 * semantics: a delete that matched nothing reports "Message not found.", not success.
 */
@Component
@RequiredArgsConstructor
public class QueueAdminTools {

  private static final String CONFIRM =
    " Requires the ADMIN role, and every call is audit-logged under your user. Confirm the exact queue and "
      + "messageId with the user before calling.";

  private final QueueService queueService;
  private final MessageService messageService;
  private final JsonMapper jsonMapper;

  @PreAuthorize("hasRole('ADMIN')")
  @McpTool(
    name = "resend_message",
    title = "Resend message",
    description = "Publish a copy of a message back onto the same queue. The original stays where it is, so the "
      + "queue then holds both. The copy gets a new messageId and carries only the payload: properties are not "
      + "copied, and nothing visible through browse_messages links it to the original. Not idempotent: every "
      + "call adds another copy, so call it once per message and never retry a success." + CONFIRM,
    annotations = @McpTool.McpAnnotations(
      readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
  public CallToolResult resendMessage(
    @McpToolParam(description = "Exact queue name, as returned by list_queues.", required = true)
    String queueName,
    @McpToolParam(description = "The messageId exactly as browse_messages returned it.", required = true)
    String messageId) {
    return run("resend_message", queueName, messageId,
      () -> messageService.resendMessage(queueName, messageId));
  }

  @PreAuthorize("hasRole('ADMIN')")
  @McpTool(
    name = "delete_message",
    title = "Delete message",
    description = "Permanently remove one message from a queue. It cannot be recovered; prefer "
      + "move_message_to_dlq if it may be needed again. If no message has that ID (it may already be gone) the "
      + "result is success=false with \"Message not found.\" and nothing changes, so a repeated call is "
      + "harmless. Can take up to 5 seconds." + CONFIRM,
    annotations = @McpTool.McpAnnotations(
      readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false))
  public CallToolResult deleteMessage(
    @McpToolParam(description = "Exact queue name, as returned by list_queues.", required = true)
    String queueName,
    @McpToolParam(description = "The messageId exactly as browse_messages returned it.", required = true)
    String messageId) {
    return run("delete_message", queueName, messageId,
      () -> messageService.deleteMessage(queueName, messageId));
  }

  @PreAuthorize("hasRole('ADMIN')")
  @McpTool(
    name = "move_message_to_dlq",
    title = "Move message to DLQ",
    description = "Move one message from a queue to the broker's dead-letter queue (DLQ by default) and remove "
      + "it from the source queue. The DLQ copy gets a new messageId and carries only the payload: properties "
      + "are dropped, so read anything you need with get_message first. If no message has that ID the result "
      + "is success=false with \"Message not found.\" and nothing changes. Can take up to 5 seconds." + CONFIRM,
    annotations = @McpTool.McpAnnotations(
      readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false))
  public CallToolResult moveMessageToDlq(
    @McpToolParam(description = "Exact queue name, as returned by list_queues.", required = true)
    String queueName,
    @McpToolParam(description = "The messageId exactly as browse_messages returned it.", required = true)
    String messageId) {
    return run("move_message_to_dlq", queueName, messageId,
      () -> messageService.moveMessageToDLQ(queueName, messageId));
  }

  /**
   * The shared shape of an admin call: refuse an unknown queue before any JMS call (browsing
   * or consuming from a missing queue auto-creates the address), run the service, translate
   * failures into controlled text, and write exactly one audit line whatever happens.
   */
  private CallToolResult run(String tool, String queueName, String messageId, Supplier<ApiResponse> call) {
    try {
      if (queueName == null || !queueService.fetchQueueNamesWithJolokia().contains(queueName)) {
        ToolAudit.record(tool, queueName, messageId, ToolAudit.REJECTED, "unknown queue");
        return ToolResults.error(ToolResults.UNKNOWN_QUEUE);
      }
      ApiResponse response = call.get();
      ToolAudit.record(tool, queueName, messageId,
        response.isSuccess() ? ToolAudit.SUCCESS : ToolAudit.FAILED, response.getMessage());
      return ToolResults.json(jsonMapper, response, !response.isSuccess());
    } catch (IllegalArgumentException e) {
      System.out.println(tool + ": rejected: " + ToolAudit.quote(e.getMessage()));
      ToolAudit.record(tool, queueName, messageId, ToolAudit.REJECTED, "invalid message ID or unreadable message");
      return ToolResults.error(ToolResults.INVALID_MESSAGE_ID);
    } catch (JMSException | JmsException | JolokiaAccessException e) {
      System.out.println(tool + ": broker error: " + ToolAudit.quote(e.getMessage()));
      ToolAudit.record(tool, queueName, messageId, ToolAudit.FAILED, "broker unavailable");
      return ToolResults.error(ToolResults.BROKER_UNAVAILABLE);
    }
  }
}
