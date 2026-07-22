package com.message.inspector.message_inspector.controller;

import com.message.inspector.message_inspector.dto.JmsMessageDto;
import com.message.inspector.message_inspector.dto.ApiResponse;
import com.message.inspector.message_inspector.service.MessageService;
import jakarta.jms.JMSException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/messages")
@RequiredArgsConstructor
public class MessageController {

  private final MessageService messageService;

  /**
   * Fetch all messages from a specific queue.
   */
  @GetMapping("/queue/{queueName}")
  public ResponseEntity<List<JmsMessageDto>> getAllMessages(@PathVariable String queueName) throws JMSException {
    List<JmsMessageDto> messages = messageService.getAllMessages(queueName);
    return ResponseEntity.ok(messages);
  }

  /**
   * Fetch a specific message by ID.
   */
  @GetMapping("/queue/{queueName}/message/{messageId}")
  public ResponseEntity<JmsMessageDto> getMessageById(@PathVariable String queueName,
                                                      @PathVariable String messageId) throws JMSException {
    return ResponseEntity.ok(messageService.getMessageById(queueName, messageId));
  }

  /**
   * Resend a message to a queue.
   */
  @PostMapping("/queue/{queueName}/message/{messageId}/resend")
  public ResponseEntity<ApiResponse> resendMessage(@PathVariable String queueName,
                                                   @PathVariable String messageId) {
    return ResponseEntity.ok(messageService.resendMessage(queueName, messageId));
  }

  /**
   * Delete a message permanently. Destructive -- restricted to ADMIN.
   */
  @PreAuthorize("hasRole('ADMIN')")
  @DeleteMapping("/queue/{queueName}/message/{messageId}")
  public ResponseEntity<ApiResponse> deleteMessage(@PathVariable String queueName,
                                                   @PathVariable String messageId) {
    return ResponseEntity.ok(messageService.deleteMessage(queueName, messageId));
  }

  /**
   * Move a message to the Dead Letter Queue (DLQ). Destructive -- restricted to ADMIN.
   */
  @PreAuthorize("hasRole('ADMIN')")
  @PostMapping("/queue/{queueName}/message/{messageId}/move-to-dlq")
  public ResponseEntity<ApiResponse> moveMessageToDLQ(@PathVariable String queueName,
                                                      @PathVariable String messageId) {
    return ResponseEntity.ok(messageService.moveMessageToDLQ(queueName, messageId));
  }
}
