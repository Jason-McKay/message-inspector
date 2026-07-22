package com.message.inspector.message_inspector.service;

import com.message.inspector.message_inspector.dto.JmsMessageDto;
import com.message.inspector.message_inspector.dto.ApiResponse;
import com.message.inspector.message_inspector.integration.artemis.JmsMessageRepository;
import jakarta.jms.JMSException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
@RequiredArgsConstructor
public class MessageService {

  private final JmsMessageRepository jmsMessageRepository;

  /**
   * Fetch all messages from a queue.
   */
  public List<JmsMessageDto> getAllMessages(String queueName) throws JMSException {
    return jmsMessageRepository.getMessagesFromQueue(queueName);
  }

  /**
   * Fetch a specific message by its unique ID.
   */
  public JmsMessageDto getMessageById(String queueName, String messageId) throws JMSException {
    return jmsMessageRepository.getMessageById(queueName, messageId);
  }

  /**
   * Resend a message using its unique ID.
   */
  public ApiResponse resendMessage(String queueName, String messageId) {
    ApiResponse apiResponse = new ApiResponse();
    try {
      JmsMessageDto message = jmsMessageRepository.getMessageById(queueName, messageId);
      if (message == null) {
        apiResponse.setSuccess(false);
        apiResponse.setMessage("Message not found.");
        return apiResponse;
      }

      // Resend to the queue the caller named, not the one round-tripped through the DTO.
      // The DTO's queueName is derived from the message's destination, so trusting it
      // made resend depend on that derivation being correct -- which it was not.
      jmsMessageRepository.sendMessage(queueName, message);

      apiResponse.setSuccess(true);
      apiResponse.setMessage("Message resent successfully.");
    } catch (JMSException e) {
      apiResponse.setSuccess(false);
      apiResponse.setMessage("Failed to resend message: " + e.getMessage());
    }

    return apiResponse;
  }

  /**
   * Delete a message using its unique ID.
   */
  public ApiResponse deleteMessage(String queueName, String messageId) {
    ApiResponse apiResponse = new ApiResponse();
    try {
      boolean deleted = jmsMessageRepository.deleteMessageById(queueName, messageId);
      apiResponse.setSuccess(deleted);
      apiResponse.setMessage(deleted ? "Message deleted successfully." : "Message not found.");
    } catch (JMSException e) {
      apiResponse.setSuccess(false);
      apiResponse.setMessage("Failed to delete message: " + e.getMessage());
    }

    return apiResponse;
  }

  /**
   * Move a message to the Dead Letter Queue (DLQ).
   */
  public ApiResponse moveMessageToDLQ(String queueName, String messageId) {
    ApiResponse apiResponse = new ApiResponse();
    try {
      boolean moved = jmsMessageRepository.moveToDeadLetterQueue(queueName, messageId);
      apiResponse.setSuccess(moved);
      apiResponse.setMessage(moved ? "Message moved to DLQ successfully." : "Message not found.");
    } catch (JMSException e) {
      apiResponse.setSuccess(false);
      apiResponse.setMessage("Failed to move message to DLQ: " + e.getMessage());
    }

    return apiResponse;
  }
}
