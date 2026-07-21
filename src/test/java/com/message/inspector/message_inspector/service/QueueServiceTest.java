package com.message.inspector.message_inspector.service;

import com.message.inspector.message_inspector.dto.JmsQueueDto;
import com.message.inspector.message_inspector.integration.artemis.JmsQueueRepository;
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
}
