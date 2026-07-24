package com.message.inspector.message_inspector.service;

import com.message.inspector.message_inspector.integration.jolokia.JolokiaRepository;
import jakarta.jms.JMSException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class QueueService {

  private final JolokiaRepository jolokiaRepository;

  /**
   * Fetch all queue names with Jolokia. Plain names -- the repository unwraps the JMX
   * envelope, so it never crosses this boundary.
   */
  public List<String> fetchQueueNamesWithJolokia() throws JMSException {
    return jolokiaRepository.fetchQueueNamesWithJolokia();
  }
}
