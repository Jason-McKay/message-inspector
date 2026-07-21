package com.message.inspector.message_inspector.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.message.inspector.message_inspector.dto.ApiResponse;
import com.message.inspector.message_inspector.dto.JmsMessageDto;
import com.message.inspector.message_inspector.security.SecurityConfig;
import com.message.inspector.message_inspector.service.MessageService;
import jakarta.jms.JMSException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Characterises the HTTP contract of {@link MessageController}: routes, path-variable
 * binding, status codes and response shape.
 * <p>
 * This is the wiring safety net for the Phase 3 package refactor. The service layer is
 * mocked -- nothing here touches a broker -- so what it protects is precisely what the
 * mock-based repository tests cannot: that the endpoints stay mapped where the Angular
 * client expects them, and that Spring can still build the context after files move.
 * <p>
 * {@link SecurityConfig} is imported deliberately rather than relying on the default
 * {@code @WebMvcTest} security chain, so the tests run against the real filter chain
 * (which currently permits {@code /api/**} unauthenticated and disables CSRF).
 */
@WebMvcTest(MessageController.class)
@Import(SecurityConfig.class)
class MessageControllerTest {

  public static final String QUEUE_NAME = "testQueue";
  public static final String MESSAGE_ID = "ID:abc-123";
  public static final String BASE = "/api/messages/queue/" + QUEUE_NAME;
  public static final String MESSAGE_BASE = BASE + "/message/" + MESSAGE_ID;

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private MessageService messageService;

  /**
   * ✅ GET /api/messages/queue/{queueName} returns the message list as a JSON array.
   */
  @Test
  void testGetAllMessages() throws Exception {
    JmsMessageDto message = new JmsMessageDto(MESSAGE_ID, "Test Payload", QUEUE_NAME,
        "2026-07-21T09:00:00Z", Map.of("JMSPriority", "4"));
    when(messageService.getAllMessages(QUEUE_NAME)).thenReturn(List.of(message));

    mockMvc.perform(get(BASE))
      .andExpect(status().isOk())
      .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
      .andExpect(jsonPath("$", hasSize(1)))
      .andExpect(jsonPath("$[0].messageId").value(MESSAGE_ID))
      .andExpect(jsonPath("$[0].payload").value("Test Payload"))
      .andExpect(jsonPath("$[0].queueName").value(QUEUE_NAME))
      .andExpect(jsonPath("$[0].timestamp").value("2026-07-21T09:00:00Z"))
      .andExpect(jsonPath("$[0].properties.JMSPriority").value("4"));

    verify(messageService, times(1)).getAllMessages(QUEUE_NAME);
  }

  /**
   * ✅ An empty queue is an empty array and a 200, not a 404.
   */
  @Test
  void testGetAllMessages_EmptyQueue() throws Exception {
    when(messageService.getAllMessages(QUEUE_NAME)).thenReturn(List.of());

    mockMvc.perform(get(BASE))
      .andExpect(status().isOk())
      .andExpect(content().json("[]"));
  }

  /**
   * ✅ GET .../message/{messageId} returns the single message.
   */
  @Test
  void testGetMessageById() throws Exception {
    when(messageService.getMessageById(QUEUE_NAME, MESSAGE_ID))
      .thenReturn(new JmsMessageDto(MESSAGE_ID, "Test Payload", QUEUE_NAME));

    mockMvc.perform(get(MESSAGE_BASE))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.messageId").value(MESSAGE_ID))
      .andExpect(jsonPath("$.payload").value("Test Payload"));

    verify(messageService, times(1)).getMessageById(QUEUE_NAME, MESSAGE_ID);
  }

  /**
   * ⚠️ A missing message is a 200 with an empty body, not a 404. There is no
   * not-found handling anywhere in the stack -- the service returns null and the
   * controller wraps it in ResponseEntity.ok(). Pinned as current behaviour, not
   * endorsed as correct.
   */
  @Test
  void testGetMessageById_NotFoundReturnsOkWithEmptyBody() throws Exception {
    when(messageService.getMessageById(QUEUE_NAME, "missing-id")).thenReturn(null);

    mockMvc.perform(get(BASE + "/message/missing-id"))
      .andExpect(status().isOk())
      .andExpect(content().string(""));
  }

  /**
   * ✅ POST .../resend delegates and returns the ApiResponse.
   */
  @Test
  void testResendMessage() throws Exception {
    when(messageService.resendMessage(QUEUE_NAME, MESSAGE_ID))
      .thenReturn(apiResponse(true, "Message resent successfully."));

    mockMvc.perform(post(MESSAGE_BASE + "/resend"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.success").value(true))
      .andExpect(jsonPath("$.message").value("Message resent successfully."));

    verify(messageService, times(1)).resendMessage(QUEUE_NAME, MESSAGE_ID);
  }

  /**
   * ✅ DELETE .../message/{messageId} delegates and returns the ApiResponse.
   */
  @Test
  void testDeleteMessage() throws Exception {
    when(messageService.deleteMessage(QUEUE_NAME, MESSAGE_ID))
      .thenReturn(apiResponse(true, "Message deleted successfully."));

    mockMvc.perform(delete(MESSAGE_BASE))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.success").value(true));

    verify(messageService, times(1)).deleteMessage(QUEUE_NAME, MESSAGE_ID);
  }

  /**
   * ✅ POST .../move-to-dlq delegates and returns the ApiResponse.
   */
  @Test
  void testMoveMessageToDLQ() throws Exception {
    when(messageService.moveMessageToDLQ(QUEUE_NAME, MESSAGE_ID))
      .thenReturn(apiResponse(true, "Message moved to DLQ successfully."));

    mockMvc.perform(post(MESSAGE_BASE + "/move-to-dlq"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.success").value(true));

    verify(messageService, times(1)).moveMessageToDLQ(QUEUE_NAME, MESSAGE_ID);
  }

  /**
   * ⚠️ A failed destructive operation is still HTTP 200 -- the service swallows
   * JMSException and reports it in the body as success=false. Any client that checks
   * only the status code will read a failure as a success. Pinned deliberately: the
   * frontend depends on this shape today.
   */
  @Test
  void testDeleteMessage_FailureIsStillHttp200() throws Exception {
    when(messageService.deleteMessage(QUEUE_NAME, MESSAGE_ID))
      .thenReturn(apiResponse(false, "Failed to delete message: broker down"));

    mockMvc.perform(delete(MESSAGE_BASE))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.success").value(false))
      .andExpect(jsonPath("$.message").value("Failed to delete message: broker down"));
  }

  /**
   * ⚠️ The read paths declare {@code throws JMSException} and nothing handles it --
   * there is no @ControllerAdvice. The exception escapes the dispatcher rather than
   * being translated into a response. Pinned so that adding error handling later is a
   * visible, deliberate change.
   */
  @Test
  void testGetAllMessages_JMSExceptionIsNotHandled() throws Exception {
    when(messageService.getAllMessages(QUEUE_NAME)).thenThrow(new JMSException("broker down"));

    Exception thrown = assertThrows(Exception.class,
        () -> mockMvc.perform(get(BASE)));

    assertInstanceOf(JMSException.class, rootCause(thrown),
        "expected the JMSException to escape unhandled");
  }

  /**
   * ✅ Queue names containing dots -- the Artemis convention -- bind intact rather than
   * being truncated as a file extension by Spring's path matching.
   */
  @Test
  void testDottedQueueNameBindsIntact() throws Exception {
    when(messageService.getAllMessages("test.queue.1")).thenReturn(List.of());

    mockMvc.perform(get("/api/messages/queue/test.queue.1"))
      .andExpect(status().isOk());

    verify(messageService).getAllMessages("test.queue.1");
    verify(messageService, times(0)).getAllMessages("test.queue");
  }

  /**
   * ❌ An unmapped path under the controller's prefix is a 404, not a mis-route onto a
   * neighbouring handler.
   */
  @Test
  void testUnmappedPathReturnsNotFound() throws Exception {
    mockMvc.perform(get("/api/messages/queue/" + QUEUE_NAME + "/nonsense"))
      .andExpect(status().isNotFound());

    verify(messageService, times(0)).getAllMessages(anyString());
  }

  private ApiResponse apiResponse(boolean success, String message) {
    ApiResponse response = new ApiResponse();
    response.setSuccess(success);
    response.setMessage(message);
    return response;
  }

  private Throwable rootCause(Throwable throwable) {
    Throwable current = throwable;
    while (current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }
}
