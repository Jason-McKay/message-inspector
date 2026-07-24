package com.message.inspector.message_inspector.service;

import com.message.inspector.message_inspector.integration.jolokia.JolokiaRepository;
import jakarta.jms.JMSException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class QueueServiceTest {

  @Mock
  private JolokiaRepository jolokiaRepository;

  @InjectMocks
  private QueueService queueService;

  /**
   * The service is a passthrough over the Jolokia queue-names path: plain names in, plain
   * names out. (The two dead JMS/Jolokia queue-list paths were deleted -- only names ever
   * worked.)
   */
  @Test
  void shouldFetchQueueNamesWithJolokia() throws JMSException {
    List<String> names = List.of("DLQ", "ExpiryQueue");
    when(jolokiaRepository.fetchQueueNamesWithJolokia()).thenReturn(names);

    assertEquals(names, queueService.fetchQueueNamesWithJolokia());
  }
}
