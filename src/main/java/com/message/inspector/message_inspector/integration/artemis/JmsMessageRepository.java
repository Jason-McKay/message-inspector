package com.message.inspector.message_inspector.integration.artemis;

import com.message.inspector.message_inspector.dto.JmsMessageDto;
import jakarta.jms.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.core.MessagePostProcessor;
import org.springframework.stereotype.Repository;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Repository
@RequiredArgsConstructor
public class JmsMessageRepository {

  private final JmsTemplate jmsTemplate;

  public static final String DLQ_NAME = "DLQ_test"; // Dead Letter Queue name

  /**
   * Characters permitted in a JMS message ID. Deliberately narrow: Artemis IDs are
   * "ID:" followed by a UUID, so this covers real IDs while excluding the quote that
   * would otherwise let a caller break out of the selector literal below.
   */
  private static final Pattern SAFE_MESSAGE_ID = Pattern.compile("[A-Za-z0-9:_.-]{1,255}");

  /**
   * Guards against JMS selector injection.
   * <p>
   * The selectors in this class are built by string concatenation, so an ID such as
   * {@code x' OR '1'='1} would otherwise widen the selector to match every message on
   * the queue — causing the wrong message to be read, consumed or moved to the DLQ.
   *
   * @throws IllegalArgumentException if the ID is null, empty or contains unsafe characters
   */
  private static String requireValidMessageId(String messageId) {
    if (messageId == null || !SAFE_MESSAGE_ID.matcher(messageId).matches()) {
      throw new IllegalArgumentException("Invalid message ID: " + messageId);
    }
    return messageId;
  }

  /**
   * Fetch all messages from a given queue.
   */
  public List<JmsMessageDto> getMessagesFromQueue(String queueName) throws JMSException {
    List<JmsMessageDto> messages = new ArrayList<>();

    jmsTemplate.browse(queueName, (session, browser) -> {
      Enumeration<?> enumeration = browser.getEnumeration();
      while (enumeration.hasMoreElements()) {
        Message message = (Message) enumeration.nextElement();
        messages.add(convertToDto(message));
      }
      return null;
    });

    return messages;
  }

  /**
   * Fetch a specific message by its unique ID.
   */
  public JmsMessageDto getMessageById(String queueName, String messageId) throws JMSException {
    requireValidMessageId(messageId);

    // Assuming Artemis supports filtering messages by ID
    return jmsTemplate.browseSelected(
      queueName,
      "JMSMessageID='" + messageId + "'",
      (session, browser) -> {
        Enumeration<?> enumeration = browser.getEnumeration();
        if (enumeration.hasMoreElements()) {
          return convertToDto((Message) enumeration.nextElement());
        }
        return null;
      }
    );
  }

  /**
   * Send a message to a queue.
   */
  public void sendMessage(String queueName, JmsMessageDto messageDto) {
    jmsTemplate.convertAndSend(queueName, messageDto.getPayload(), new MessagePostProcessor() {
      @Override
      public Message postProcessMessage(Message message) throws JMSException {
        message.setJMSCorrelationID(messageDto.getMessageId());
        return message;
      }
    });
  }

  /**
   * Delete a message by ID (if applicable).
   */
  public void deleteMessageById(String queueName, String messageId) throws JMSException {
    // JMS does not support direct message deletion, so this requires an alternative approach.
    // The below will consume the message selectively with a filter, ensuring it does not get reprocessed.
    requireValidMessageId(messageId);

    jmsTemplate.execute(session -> {
      Queue queue = session.createQueue(queueName);
      MessageConsumer consumer = session.createConsumer(queue, "JMSMessageID='" + messageId + "'");

      Message message = consumer.receive(5000); // 5-second timeout

      if (message != null) {
        System.out.println("Deleting message: " + message.getJMSMessageID());
        message.acknowledge(); // Marks the message as consumed, preventing reprocessing
      } else {
        System.out.println("No matching message found for deletion.");
      }

      consumer.close();
      return null;
    }, true);
  }

  /**
   * Moves a message to the Dead Letter Queue (DLQ) instead of deleting it permanently.
   */
  public void moveToDeadLetterQueue(String queueName, String messageId) throws JMSException {
    requireValidMessageId(messageId);

    jmsTemplate.execute(session -> {
      Queue queue = session.createQueue(queueName);

      MessageConsumer consumer = session.createConsumer(queue, "JMSMessageID='" + messageId + "'");
      Message message = consumer.receive(5000); // 5-second timeout

      if (message != null) {
        // Convert to DTO and send to DLQ
        JmsMessageDto dto = convertToDto(message);
        sendMessage(DLQ_NAME, dto);

        // Acknowledge and commit transaction
        message.acknowledge();
      } else {
        System.out.println("No matching message found to move.");
      }

      consumer.close();
      return null;
    }, true);
  }

  /**
   * Convert JMS Message to DTO
   */
  private JmsMessageDto convertToDto(Message message) throws JMSException {
    if (message instanceof TextMessage textMessage) {
      // 2) Extract the payload and headers from the message
      return new JmsMessageDto(
        message.getJMSMessageID(),
        textMessage.getText(),
        destinationName(message.getJMSDestination()),
        String.valueOf(message.getJMSTimestamp()),
        extractHeaders(message)
      );
    }
    throw new IllegalArgumentException("Unsupported message type.");
  }

  /**
   * The destination's actual name.
   * <p>
   * This used to be {@code destination.toString()}, which on Artemis yields
   * {@code ActiveMQQueue[DLQ]} rather than {@code DLQ}. That string was handed to the UI
   * and, worse, fed back into {@code sendMessage} on resend -- creating and delivering to
   * an address literally named {@code ActiveMQQueue[DLQ]} while reporting success.
   */
  private String destinationName(Destination destination) throws JMSException {
    if (destination instanceof Queue queue) {
      return queue.getQueueName();
    }
    if (destination instanceof Topic topic) {
      return topic.getTopicName();
    }
    return destination == null ? null : destination.toString();
  }

  /**
   * Extract headers from the JMS message
   */
  private Map<String, String> extractHeaders(Message message) {
    Map<String, String> headers = new java.util.HashMap<>();
    try {
      Enumeration<?> propertyNames = message.getPropertyNames();
      while (propertyNames.hasMoreElements()) {
        String propertyName = (String) propertyNames.nextElement();
        headers.put(propertyName, message.getStringProperty(propertyName));
      }
    } catch (JMSException e) {
      System.out.println("JmsMessageRepository.java Error extracting headers: " + e.getMessage());
    }
    return headers;
  }
}
