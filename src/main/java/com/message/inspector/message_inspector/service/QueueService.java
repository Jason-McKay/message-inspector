package com.message.inspector.message_inspector.service;

import com.message.inspector.message_inspector.dto.JmsQueueDto;
import com.message.inspector.message_inspector.integration.artemis.JmsQueueRepository;
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
   * Fetch all queue names with Jolokia. Plain names -- the repository unwraps the JMX
   * envelope, so it never crosses this boundary.
   */
  public List<String> fetchQueueNamesWithJolokia() throws JMSException {
    return jolokiaRepository.fetchQueueNamesWithJolokia();
  }
}
