package com.message.inspector.message_inspector.service;

import com.message.inspector.message_inspector.model.JmsQueueDto;
import com.message.inspector.message_inspector.model.JolokiaResponse;
import com.message.inspector.message_inspector.repository.JmsQueueRepository;
import com.message.inspector.message_inspector.integration.jolokia.JolokiaRepository;
import jakarta.jms.JMSException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class QueueService {

  private final JmsQueueRepository jmsQueueRepository;
  private final JolokiaRepository jolokiaRepository;

  /**
   * Fetch all queues.
   */
  public List<JmsQueueDto> fetchQueuesWithArtemis() throws JMSException {
    return jmsQueueRepository.fetchQueuesWithArtemis();
  }

  /**
   * Fetch all queues from Jolokia.
   */
  public List<JmsQueueDto> fetchQueuesWithJolokia() throws JMSException {
    return jolokiaRepository.fetchQueuesWithJolokia();
  }

  /**
   * Fetch all queue details with Jolokia.
   */
  public JolokiaResponse<List<String>> fetchQueueNamesWithJolokia() throws JMSException {
    return jolokiaRepository.fetchQueueNamesWithJolokia();
  }
}
