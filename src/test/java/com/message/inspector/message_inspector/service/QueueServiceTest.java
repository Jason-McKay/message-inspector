package com.message.inspector.message_inspector.service;

import com.message.inspector.message_inspector.dto.JmsQueueDto;
import com.message.inspector.message_inspector.integration.artemis.JmsQueueRepository;
import com.message.inspector.message_inspector.integration.jolokia.JolokiaRepository;
import jakarta.jms.JMSException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class QueueServiceTest {
  @Mock
  private JmsQueueRepository queueRepository;

  @Mock
  private JolokiaRepository jolokiaRepository;

  @InjectMocks
  private QueueService queueService;

  @Test
  void shouldFetchQueuesWithArtemis() throws JMSException {
    List<JmsQueueDto> queues = new ArrayList<>();
    String[] queueNames = {"test.queue.1", "test.queue.2", "test.queue.3"};
    int count = 1;
    for (String queueName : queueNames) {
      JmsQueueDto jmsQueueDto = new JmsQueueDto();
      jmsQueueDto.setQueueId(String.valueOf(count));
      jmsQueueDto.setQueueName(queueName);
      jmsQueueDto.setMessageCount(count * 10); // Placeholder for message count
      queues.add(jmsQueueDto);
    }
    when(queueRepository.fetchQueuesWithArtemis()).thenReturn(queues);

    List<JmsQueueDto> result = queueService.fetchQueuesWithArtemis();

    assertEquals(queues, result);
  }

  /**
   * The Jolokia path had zero service-level coverage before the envelope unwrap changed
   * this seam. The service is a passthrough: plain names in, plain names out.
   */
  @Test
  void shouldFetchQueueNamesWithJolokia() throws JMSException {
    List<String> names = List.of("DLQ", "ExpiryQueue");
    when(jolokiaRepository.fetchQueueNamesWithJolokia()).thenReturn(names);

    assertEquals(names, queueService.fetchQueueNamesWithJolokia());
  }
}
