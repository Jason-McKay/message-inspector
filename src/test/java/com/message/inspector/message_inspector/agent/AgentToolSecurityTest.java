package com.message.inspector.message_inspector.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.message.inspector.message_inspector.dto.ApiResponse;
import com.message.inspector.message_inspector.service.MessageService;
import com.message.inspector.message_inspector.service.QueueService;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import jakarta.jms.JMSException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

/**
 * Pins the identity path for the MCP tools, without a broker: the admin tools are behind
 * the same {@code @PreAuthorize("hasRole('ADMIN')")} as the REST destructive endpoints, a
 * denial is audited even though it never reaches the method, and the read-only tools are
 * open to any authenticated user.
 * <p>
 * The {@code /mcp} cases go through the real filter chain and the real MCP router, so they
 * prove what the direct bean calls cannot: that the JWT authentication is still on the
 * thread when the tool runs. The services are mocked, so nothing here touches a broker.
 * {@code McpServerIT} repeats the important cases with a real MCP client and a real broker.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class AgentToolSecurityTest {

  public static final String QUEUE_NAME = "testQueue";
  public static final String MESSAGE_ID = "ID:1";
  public static final String DELETE_CALL = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":"
    + "{\"name\":\"delete_message\",\"arguments\":{\"queueName\":\"testQueue\",\"messageId\":\"ID:1\"}}}";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private QueueAdminTools adminTools;

  @Autowired
  private QueueInspectionTools inspectionTools;

  @MockitoBean
  private QueueService queueService;

  @MockitoBean
  private MessageService messageService;

  @BeforeEach
  void setUp() throws JMSException {
    when(queueService.fetchQueueNamesWithJolokia()).thenReturn(List.of(QUEUE_NAME, "DLQ"));
    ApiResponse deleted = new ApiResponse();
    deleted.setSuccess(true);
    deleted.setMessage("Message deleted successfully.");
    when(messageService.deleteMessage(anyString(), anyString())).thenReturn(deleted);
  }

  /**
   * ✅ The admin tools bean is the method-security proxy, not the bare class. If it were
   * not, every other assertion here would be about a check that never runs in production.
   */
  @Test
  void testAdminToolsAreProxied() {
    assertTrue(AopUtils.isAopProxy(adminTools));
  }

  /**
   * ❌ A USER is denied all three admin tools; the service is never called and each denial
   * is audited under the caller's name.
   */
  @Test
  @WithMockUser(username = "jason", roles = "USER")
  void testUserIsDeniedAdminTools(CapturedOutput output) {
    assertThrows(AuthorizationDeniedException.class, () -> adminTools.resendMessage(QUEUE_NAME, MESSAGE_ID));
    assertThrows(AuthorizationDeniedException.class, () -> adminTools.deleteMessage(QUEUE_NAME, MESSAGE_ID));
    assertThrows(AuthorizationDeniedException.class, () -> adminTools.moveMessageToDlq(QUEUE_NAME, MESSAGE_ID));

    verifyNoInteractions(messageService);
    for (String tool : List.of("resend_message", "delete_message", "move_message_to_dlq")) {
      assertTrue(output.getOut().contains("AUDIT agent-tool=" + tool + " user=\"jason\" queue=\"testQueue\" "
        + "messageId=\"ID:1\" outcome=denied result=\"Access denied\""), tool);
    }
  }

  /**
   * ✅ An ADMIN is let through to the service.
   */
  @Test
  @WithMockUser(username = "admin", roles = "ADMIN")
  void testAdminIsAllowedAdminTools() {
    CallToolResult result = adminTools.deleteMessage(QUEUE_NAME, MESSAGE_ID);

    assertFalse(result.isError());
    verify(messageService).deleteMessage(QUEUE_NAME, MESSAGE_ID);
  }

  /**
   * ✅ The read-only tools need authentication only, not ADMIN.
   */
  @Test
  @WithMockUser(username = "jason", roles = "USER")
  void testUserCanUseReadOnlyTools() throws JMSException {
    CallToolResult result = inspectionTools.listQueues();

    assertFalse(result.isError());
    verify(queueService).fetchQueueNamesWithJolokia();
  }

  /**
   * ❌ End to end over /mcp: a USER's bearer token reaches the tool, method security denies
   * it, and the agent gets an error result rather than a 403 -- the HTTP exchange itself
   * succeeded. The message service is never called and the denial is audited.
   */
  @Test
  void testMcpDeleteAsUserIsDenied(CapturedOutput output) throws Exception {
    mockMvc.perform(post("/mcp")
        .with(jwt().jwt(token -> token.subject("jason")).authorities(new SimpleGrantedAuthority("ROLE_USER")))
        .contentType("application/json")
        .header("Accept", "application/json, text/event-stream")
        .content(DELETE_CALL))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.result.isError").value(true));

    verifyNoInteractions(messageService);
    assertTrue(output.getOut().contains("AUDIT agent-tool=delete_message user=\"jason\" queue=\"testQueue\" "
      + "messageId=\"ID:1\" outcome=denied"));
  }

  /**
   * ✅ End to end over /mcp: an ADMIN's token is let through, and the audit line names the
   * token's subject.
   */
  @Test
  void testMcpDeleteAsAdminIsAllowed(CapturedOutput output) throws Exception {
    mockMvc.perform(post("/mcp")
        .with(jwt().jwt(token -> token.subject("admin")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
        .contentType("application/json")
        .header("Accept", "application/json, text/event-stream")
        .content(DELETE_CALL))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.result.isError").value(false));

    verify(messageService).deleteMessage(QUEUE_NAME, MESSAGE_ID);
    assertTrue(output.getOut().contains("AUDIT agent-tool=delete_message user=\"admin\" queue=\"testQueue\" "
      + "messageId=\"ID:1\" outcome=success"));
  }
}
