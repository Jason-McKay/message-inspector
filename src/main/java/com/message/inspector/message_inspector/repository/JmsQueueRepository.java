package com.message.inspector.message_inspector.repository;

import com.message.inspector.message_inspector.model.JmsQueueDto;
import jakarta.jms.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class JmsQueueRepository {

  private final JmsTemplate jmsTemplate;

  public static final String DLQ_NAME = "DLQ_test"; // Dead Letter Queue name

  /**
   * Fetch queues.
   */
  public List<JmsQueueDto> fetchQueuesWithArtemis() throws JMSException {
    List<JmsQueueDto> queues = new ArrayList<>();

    try (Connection connection = jmsTemplate.getConnectionFactory().createConnection()) {
      connection.start();
      Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
      QueueBrowser browser = session.createBrowser(null);

      // Use ActiveMQ specific management API Artemis queues are typically retrieved via JMX or Jolokia

      // Placeholder for queues
      String[] queueNames = {"test.queue.1", "test.queue.2", "test.queue.3"};
      int count = 1;
      for (String queueName : queueNames) {
        JmsQueueDto jmsQueueDto = new JmsQueueDto();
        jmsQueueDto.setQueueId(String.valueOf(count));
        jmsQueueDto.setQueueName(queueName);
        jmsQueueDto.setMessageCount(count * 10); // Placeholder for message count
        queues.add(jmsQueueDto);
      }
    } catch (Exception e) {
      // Handle exception
      System.out.println("JmsQueueRepository.java: Error fetching queues: " + e.getMessage());
    }

    return queues;
  }
}
