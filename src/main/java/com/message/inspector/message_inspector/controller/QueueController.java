package com.message.inspector.message_inspector.controller;

import com.message.inspector.message_inspector.dto.JmsQueueDto;
import com.message.inspector.message_inspector.service.QueuePreferenceService;
import com.message.inspector.message_inspector.service.QueueService;
import jakarta.jms.JMSException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
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
   * Fetch all queue names from Artemis using Jolokia. Returns a bare JSON array: the
   * Jolokia JMX envelope is unwrapped in the repository and no longer leaks to clients.
   */
  @GetMapping("/artemis-jolokia/names")
  public ResponseEntity<List<String>> fetchQueueNamesWithJolokia() throws JMSException {
    List<String> queues = queueService.fetchQueueNamesWithJolokia();
    return ResponseEntity.ok(queues);
  }

  /**
   * Save the last used queue for the authenticated user.
   * <p>
   * The user id is taken from the security principal, not the request, so a caller can
   * only ever write their own preference. This replaces the old {@code {userId}} path
   * variable that trusted the client to name itself (and hardcoded {@code 'jason'} in the
   * UI).
   */
  @PostMapping("/last-used")
  public ResponseEntity<Void> saveLastUsedQueue(Principal principal, @RequestBody String queueName) {
    queuePreferenceService.saveLastUsedQueue(principal.getName(), queueName);
    return ResponseEntity.ok().build();
  }

  /**
   * Get the last used queue for the authenticated user. Absence is a 200 with an empty
   * body, not a 404 -- a user with no stored preference yet is the normal first run.
   */
  @GetMapping("/last-used")
  public ResponseEntity<String> getLastUsedQueue(Principal principal) {
    String queueName = queuePreferenceService.getLastUsedQueue(principal.getName());
    return ResponseEntity.ok(queueName);
  }
}
