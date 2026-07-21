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
      jmsMessageRepository.sendMessage(message.getQueueName(), message);

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
      jmsMessageRepository.deleteMessageById(queueName, messageId);
      apiResponse.setSuccess(true);
      apiResponse.setMessage("Message deleted successfully.");
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
      jmsMessageRepository.moveToDeadLetterQueue(queueName, messageId);
      apiResponse.setSuccess(true);
      apiResponse.setMessage("Message moved to DLQ successfully.");
    } catch (JMSException e) {
      apiResponse.setSuccess(false);
      apiResponse.setMessage("Failed to move message to DLQ: " + e.getMessage());
    }

    return apiResponse;
  }
}
