package com.message.inspector.message_inspector.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.message.inspector.message_inspector.dto.ApiResponse;
import com.message.inspector.message_inspector.integration.jolokia.JolokiaAccessException;
import com.message.inspector.message_inspector.service.MessageService;
import com.message.inspector.message_inspector.service.QueueService;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import jakarta.jms.JMSException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * The admin tools' behaviour once method security has let a call through: delegation,
 * truthful results, the guardrails, and the audit line. The role check itself needs the
 * Spring proxy, so it is covered by {@code AgentToolSecurityTest}.
 */
@ExtendWith(OutputCaptureExtension.class)
class QueueAdminToolsTest {

  public static final String QUEUE_NAME = "testQueue";
  public static final String MESSAGE_ID = "ID:1";

  @Mock
  private QueueService queueService;

  @Mock
  private MessageService messageService;

  private final JsonMapper jsonMapper = JsonMapper.builder().build();

  private QueueAdminTools tools;

  @BeforeEach
  void setUp() throws JMSException {
    MockitoAnnotations.openMocks(this);
    tools = new QueueAdminTools(queueService, messageService, jsonMapper);
    when(queueService.fetchQueueNamesWithJolokia()).thenReturn(List.of(QUEUE_NAME, "DLQ"));
    SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("admin", null, "ROLE_ADMIN"));
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  /**
   * ✅ delete_message delegates to the service, returns its response as JSON, and audits
   * who deleted what.
   */
  @Test
  void testDeleteMessage(CapturedOutput output) {
    when(messageService.deleteMessage(QUEUE_NAME, MESSAGE_ID)).thenReturn(response(true, "Message deleted successfully."));

    CallToolResult result = tools.deleteMessage(QUEUE_NAME, MESSAGE_ID);

    assertFalse(result.isError());
    JsonNode body = jsonMapper.readTree(text(result));
    assertTrue(body.get("success").asBoolean());
    assertEquals("Message deleted successfully.", body.get("message").asString());
    verify(messageService).deleteMessage(QUEUE_NAME, MESSAGE_ID);
    assertTrue(output.getOut().contains("AUDIT agent-tool=delete_message user=\"admin\" queue=\"testQueue\" "
      + "messageId=\"ID:1\" outcome=success result=\"Message deleted successfully.\""));
  }

  /**
   * ❌ A delete that matched nothing is reported truthfully -- an error result carrying
   * the service's "Message not found." -- and audited as failed.
   */
  @Test
  void testDeleteMessage_NotFound(CapturedOutput output) {
    when(messageService.deleteMessage(QUEUE_NAME, MESSAGE_ID)).thenReturn(response(false, "Message not found."));

    CallToolResult result = tools.deleteMessage(QUEUE_NAME, MESSAGE_ID);

    assertTrue(result.isError());
    JsonNode body = jsonMapper.readTree(text(result));
    assertFalse(body.get("success").asBoolean());
    assertEquals("Message not found.", body.get("message").asString());
    assertTrue(output.getOut().contains("agent-tool=delete_message user=\"admin\" queue=\"testQueue\" "
      + "messageId=\"ID:1\" outcome=failed result=\"Message not found.\""));
  }

  /**
   * ✅ move_message_to_dlq delegates and audits.
   */
  @Test
  void testMoveMessageToDlq(CapturedOutput output) {
    when(messageService.moveMessageToDLQ(QUEUE_NAME, MESSAGE_ID)).thenReturn(response(true, "Message moved to DLQ successfully."));

    CallToolResult result = tools.moveMessageToDlq(QUEUE_NAME, MESSAGE_ID);

    assertFalse(result.isError());
    verify(messageService).moveMessageToDLQ(QUEUE_NAME, MESSAGE_ID);
    assertTrue(output.getOut().contains("AUDIT agent-tool=move_message_to_dlq user=\"admin\" queue=\"testQueue\" "
      + "messageId=\"ID:1\" outcome=success result=\"Message moved to DLQ successfully.\""));
  }

  /**
   * ✅ resend_message delegates and audits.
   */
  @Test
  void testResendMessage(CapturedOutput output) {
    when(messageService.resendMessage(QUEUE_NAME, MESSAGE_ID)).thenReturn(response(true, "Message resent successfully."));

    CallToolResult result = tools.resendMessage(QUEUE_NAME, MESSAGE_ID);

    assertFalse(result.isError());
    verify(messageService).resendMessage(QUEUE_NAME, MESSAGE_ID);
    assertTrue(output.getOut().contains("AUDIT agent-tool=resend_message user=\"admin\" queue=\"testQueue\" "
      + "messageId=\"ID:1\" outcome=success result=\"Message resent successfully.\""));
  }

  /**
   * ❌ An unknown queue is refused before the service (and so the broker) is touched, and
   * the attempt is still audited.
   */
  @Test
  void testUnknownQueueIsRejectedAndAudited(CapturedOutput output) {
    CallToolResult result = tools.moveMessageToDlq("DLQ_typo", MESSAGE_ID);

    assertTrue(result.isError());
    assertEquals(ToolResults.UNKNOWN_QUEUE, text(result));
    verifyNoInteractions(messageService);
    assertTrue(output.getOut().contains("agent-tool=move_message_to_dlq user=\"admin\" queue=\"DLQ_typo\" "
      + "messageId=\"ID:1\" outcome=rejected"));
  }

  /**
   * ❌ A malformed ID is rejected without echoing it back to the agent.
   */
  @Test
  void testInvalidIdIsRejectedWithoutEcho(CapturedOutput output) {
    String injection = "x' OR '1'='1";
    when(messageService.deleteMessage(QUEUE_NAME, injection))
      .thenThrow(new IllegalArgumentException("Invalid message ID: " + injection));

    CallToolResult result = tools.deleteMessage(QUEUE_NAME, injection);

    assertTrue(result.isError());
    assertEquals(ToolResults.INVALID_MESSAGE_ID, text(result));
    assertTrue(output.getOut().contains("agent-tool=delete_message user=\"admin\" queue=\"testQueue\" "
      + "messageId=\"x' OR '1'='1\" outcome=rejected"));
  }

  /**
   * ❌ A broker failure during the queue check is reported as unavailability and audited
   * as failed.
   */
  @Test
  void testBrokerUnavailable(CapturedOutput output) throws JMSException {
    when(queueService.fetchQueueNamesWithJolokia()).thenThrow(new JolokiaAccessException("connection refused"));

    CallToolResult result = tools.deleteMessage(QUEUE_NAME, MESSAGE_ID);

    assertTrue(result.isError());
    assertEquals(ToolResults.BROKER_UNAVAILABLE, text(result));
    verifyNoInteractions(messageService);
    assertTrue(output.getOut().contains("agent-tool=delete_message user=\"admin\" queue=\"testQueue\" "
      + "messageId=\"ID:1\" outcome=failed result=\"broker unavailable\""));
  }

  /**
   * ❌ A message ID carrying a newline cannot forge a second audit line: control characters
   * are replaced and quotes escaped.
   */
  @Test
  void testAuditLineCannotBeForged(CapturedOutput output) {
    String forged = "ID:1\nAUDIT agent-tool=delete_message user=\"someone-else\" outcome=success";
    when(messageService.deleteMessage(QUEUE_NAME, forged))
      .thenThrow(new IllegalArgumentException("Invalid message ID: " + forged));

    tools.deleteMessage(QUEUE_NAME, forged);

    long auditLines = output.getOut().lines().filter(line -> line.startsWith("AUDIT")).count();
    assertEquals(1, auditLines);
    assertTrue(output.getOut().contains(
      "messageId=\"ID:1?AUDIT agent-tool=delete_message user=\\\"someone-else\\\" outcome=success\""));
  }

  /**
   * ✅ Audit values are capped in length, so one call cannot write an unbounded log line.
   */
  @Test
  void testAuditValueIsCapped() {
    String quoted = ToolAudit.quote("q".repeat(1000));

    assertTrue(quoted.length() < 300);
    assertTrue(quoted.endsWith("...\""));
  }

  /**
   * ✅ With no authentication on the thread the audit line says so rather than failing.
   */
  @Test
  void testAuditWithoutAuthentication(CapturedOutput output) {
    SecurityContextHolder.clearContext();
    when(messageService.deleteMessage(QUEUE_NAME, MESSAGE_ID)).thenReturn(response(true, "Message deleted successfully."));

    tools.deleteMessage(QUEUE_NAME, MESSAGE_ID);

    assertTrue(output.getOut().contains("agent-tool=delete_message user=\"anonymous\""));
  }

  private static ApiResponse response(boolean success, String message) {
    ApiResponse response = new ApiResponse();
    response.setSuccess(success);
    response.setMessage(message);
    return response;
  }

  private static String text(CallToolResult result) {
    return ((TextContent) result.content().get(0)).text();
  }
}
