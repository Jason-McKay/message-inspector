package com.message.inspector.message_inspector.controller;

import com.message.inspector.message_inspector.dto.JmsQueueDto;
import com.message.inspector.message_inspector.dto.JolokiaResponse;
import com.message.inspector.message_inspector.service.QueuePreferenceService;
import com.message.inspector.message_inspector.service.QueueService;
import jakarta.jms.JMSException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Controller for handling queue-related requests.
 */
@RestController
@RequestMapping("/api/queues")
@RequiredArgsConstructor
public class QueueController {

  private final QueueService queueService;
  private final QueuePreferenceService queuePreferenceService;

  /**
   * Fetch all queues from Artemis straight.
   */
  @GetMapping("/artemis-straight")
  public ResponseEntity<List<JmsQueueDto>> fetchQueuesWithArtemis() throws JMSException {
    List<JmsQueueDto> queues = queueService.fetchQueuesWithArtemis();
    return ResponseEntity.ok(queues);
  }

  /**
   * Fetch all queues from Artemis using Jolokia.
   */
  @GetMapping("/artemis-jolokia")
  public ResponseEntity<List<JmsQueueDto>> fetchQueuesWithJolokia() throws JMSException {
    List<JmsQueueDto> queues = queueService.fetchQueuesWithJolokia();
    return ResponseEntity.ok(queues);
  }

  /**
   * Fetch all queue names from Artemis using Jolokia.
   */
  @GetMapping("/artemis-jolokia/names")
  public ResponseEntity<JolokiaResponse<List<String>>> fetchQueueNamesWithJolokia() throws JMSException {
    JolokiaResponse<List<String>> queues = queueService.fetchQueueNamesWithJolokia();
    return ResponseEntity.ok(queues);
  }

  /**
   * Save the last used queue for a user.
   */
  @PostMapping("/last-used/{userId}")
  public ResponseEntity<Void> saveLastUsedQueue(@PathVariable String userId, @RequestBody String queueName) {
    queuePreferenceService.saveLastUsedQueue(userId, queueName);
    return ResponseEntity.ok().build();
  }

  /**
   * Get the last used queue for a user.
   */
  @GetMapping("/last-used/{userId}")
  public ResponseEntity<String> getLastUsedQueue(@PathVariable String userId) {
    String queueName = queuePreferenceService.getLastUsedQueue(userId);
    return ResponseEntity.ok(queueName);
  }
}
