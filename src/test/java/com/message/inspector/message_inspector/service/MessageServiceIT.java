package com.message.inspector.message_inspector.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.message.inspector.message_inspector.dto.ApiResponse;
import com.message.inspector.message_inspector.dto.JmsMessageDto;
import com.message.inspector.message_inspector.integration.artemis.JmsMessageRepository;
import jakarta.jms.JMSException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The only automated test in the project that opens a real JMS connection.
 * <p>
 * Everything else at the message level is Mockito-based, which means a fully green suite
 * has historically said nothing about whether the client can actually talk to a broker.
 * Three real defects during Phase 3 were found by driving the running app by hand for
 * exactly this reason. This closes that gap for the core message operations.
 * <p>
 * <strong>This class only runs under {@code ./mvnw verify}</strong> (failsafe), not
 * {@code ./mvnw test} (surefire), and needs Docker. Previously it ran under neither: it
 * compiled but was never executed, and its setup assumed a broker someone had seeded by
 * hand.
 * <p>
 * It is self-contained now -- it starts its own broker, seeds its own message, and drains
 * both queues before each test so cases cannot leak into one another. Pointing Spring at
 * the container is only possible because {@code ArtemisConfig} reads its broker URL from
 * properties; it used to hardcode {@code localhost:61616}.
 */
@SpringBootTest
@Testcontainers
class MessageServiceIT {

  private static final String TEST_QUEUE = "testQueue";
  private static final String PAYLOAD = "Test Payload";

  /**
   * Pinned to the same image and version as the shared dev stack in ../infrastructure so
   * the test exercises the client/broker pairing that is actually run locally.
   */
  @Container
  static final GenericContainer<?> ARTEMIS =
      new GenericContainer<>("apache/activemq-artemis:2.31.2")
        .withExposedPorts(61616)
        .withEnv("ARTEMIS_USER", "admin")
        .withEnv("ARTEMIS_PASSWORD", "admin")
        .withEnv("ANONYMOUS_LOGIN", "false")
        .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));

  @DynamicPropertySource
  static void brokerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.artemis.broker-url",
        () -> "tcp://" + ARTEMIS.getHost() + ":" + ARTEMIS.getMappedPort(61616));
    registry.add("spring.artemis.user", () -> "admin");
    registry.add("spring.artemis.password", () -> "admin");
  }

  @Autowired
  private JmsMessageRepository jmsMessageRepository;

  @Autowired
  private MessageService messageService;

  /**
   * The configured DLQ name, resolved the same way the repository resolves it. Asserting
   * against this rather than a hardcoded constant is what lets the move test catch the old
   * "DLQ_test" bug -- the constant it used to assert against passed whatever it said.
   */
  @Value("${message-inspector.dlq-name:DLQ}")
  private String dlqName;

  private String messageId;

  @BeforeEach
  void seedOneMessage() throws JMSException {
    drain(TEST_QUEUE);
    drain(dlqName);

    jmsMessageRepository.sendMessage(TEST_QUEUE, new JmsMessageDto(null, PAYLOAD, TEST_QUEUE));

    List<JmsMessageDto> messages = messageService.getAllMessages(TEST_QUEUE);
    assertThat(messages).hasSize(1);
    messageId = messages.getFirst().getMessageId();
  }

  /**
   * ✅ A browse returns the seeded message over a real connection.
   */
  @Test
  void testGetAllMessages() throws JMSException {
    List<JmsMessageDto> messages = messageService.getAllMessages(TEST_QUEUE);

    assertThat(messages).hasSize(1);
    assertThat(messages.getFirst().getPayload()).isEqualTo(PAYLOAD);
  }

  /**
   * ✅ Fetching by ID goes through a real JMS selector -- the string-concatenated path
   * that the SAFE_MESSAGE_ID guard protects. Mock tests can prove the guard is called;
   * only this can prove the selector the broker receives actually matches.
   */
  @Test
  void testGetMessageById() throws JMSException {
    JmsMessageDto message = messageService.getMessageById(TEST_QUEUE, messageId);

    assertThat(message).isNotNull();
    assertThat(message.getMessageId()).isEqualTo(messageId);
    assertThat(message.getPayload()).isEqualTo(PAYLOAD);
  }

  /**
   * ❌ An ID that does not exist yields null rather than an exception.
   */
  @Test
  void testGetMessageById_Missing() throws JMSException {
    assertThat(messageService.getMessageById(TEST_QUEUE, "ID:does-not-exist")).isNull();
  }

  /**
   * ✅ Delete really consumes the message: it is gone from the broker afterwards, not
   * merely reported as deleted.
   */
  @Test
  void testDeleteMessage() throws JMSException {
    assertThat(messageService.deleteMessage(TEST_QUEUE, messageId).isSuccess()).isTrue();

    assertThat(messageService.getMessageById(TEST_QUEUE, messageId)).isNull();
    assertThat(messageService.getAllMessages(TEST_QUEUE)).isEmpty();
  }

  /**
   * ✅ Move-to-DLQ consumes from the source queue and produces to the DLQ. Asserting both
   * halves matters -- a move that only deletes would look identical from the source side.
   */
  @Test
  void testMoveMessageToDLQ() throws JMSException {
    assertThat(messageService.moveMessageToDLQ(TEST_QUEUE, messageId).isSuccess()).isTrue();

    assertThat(messageService.getAllMessages(TEST_QUEUE)).isEmpty();
    assertThat(messageService.getAllMessages(dlqName))
      .extracting(JmsMessageDto::getPayload)
      .contains(PAYLOAD);
  }

  /**
   * ❌ Deleting an ID that does not exist reports failure rather than success, and leaves
   * the queue untouched. Before the fix the repository returned void and the service
   * reported success for a delete that consumed nothing -- the "already consumed by
   * someone else" race is the normal failure in a queue browser, so this mattered.
   */
  @Test
  void testDeleteMessage_Missing() throws JMSException {
    ApiResponse response = messageService.deleteMessage(TEST_QUEUE, "ID:does-not-exist");

    assertThat(response.isSuccess()).isFalse();
    assertThat(response.getMessage()).isEqualTo("Message not found.");
    assertThat(messageService.getAllMessages(TEST_QUEUE)).hasSize(1);
  }

  /**
   * ❌ Moving an ID that does not exist reports failure, moves nothing to the DLQ, and
   * leaves the source queue untouched. Same false-success bug as delete.
   */
  @Test
  void testMoveMessageToDLQ_Missing() throws JMSException {
    ApiResponse response = messageService.moveMessageToDLQ(TEST_QUEUE, "ID:does-not-exist");

    assertThat(response.isSuccess()).isFalse();
    assertThat(response.getMessage()).isEqualTo("Message not found.");
    assertThat(messageService.getAllMessages(TEST_QUEUE)).hasSize(1);
    assertThat(messageService.getAllMessages(dlqName)).isEmpty();
  }

  /**
   * ✅ A browsed message carries the queue's real name.
   * <p>
   * This is the regression test for the resend bug: {@code convertToDto} used to derive
   * queueName from {@code destination.toString()}, which on Artemis is
   * {@code ActiveMQQueue[testQueue]}. That wrong value was shown in the UI and, fatally,
   * fed back into resend. Before the fix this assertion fails on the real broker while
   * every mock-based test still passes, because the mocks supply a clean name the real
   * code path never produced.
   */
  @Test
  void testBrowsedMessageCarriesRealQueueName() throws JMSException {
    JmsMessageDto message = messageService.getAllMessages(TEST_QUEUE).getFirst();

    assertThat(message.getQueueName()).isEqualTo(TEST_QUEUE);
  }

  /**
   * ✅ Resend puts a copy back on the queue it came from, and the copy is a real message
   * on that queue rather than one delivered to an address named after a toString().
   * Browse is non-destructive, so the original is still there and the count goes to two.
   */
  @Test
  void testResendMessage() throws JMSException {
    assertThat(messageService.resendMessage(TEST_QUEUE, messageId).isSuccess()).isTrue();

    assertThat(messageService.getAllMessages(TEST_QUEUE))
      .hasSize(2)
      .allSatisfy(message -> {
        assertThat(message.getPayload()).isEqualTo(PAYLOAD);
        assertThat(message.getQueueName()).isEqualTo(TEST_QUEUE);
      });
  }

  /**
   * ✅ Resending an ID that does not exist reports failure rather than throwing, and
   * sends nothing.
   */
  @Test
  void testResendMessage_Missing() throws JMSException {
    assertThat(messageService.resendMessage(TEST_QUEUE, "ID:does-not-exist").isSuccess())
      .isFalse();

    assertThat(messageService.getAllMessages(TEST_QUEUE)).hasSize(1);
  }

  /**
   * ❌ The selector-injection guard rejects a crafted ID against a live broker, and
   * consumes nothing. The mock tests assert the guard is invoked; this asserts the queue
   * is genuinely untouched.
   */
  @Test
  void testSelectorInjectionIsRejectedAndConsumesNothing() throws JMSException {
    assertThatThrownBy(() -> messageService.getMessageById(TEST_QUEUE, "x' OR '1'='1"))
      .isInstanceOf(IllegalArgumentException.class);

    assertThat(messageService.getAllMessages(TEST_QUEUE)).hasSize(1);
  }

  /**
   * Removes every message from a queue using the application's own browse and delete
   * paths, so draining cannot succeed by a route the production code does not have. The
   * iteration cap stops a broken delete from spinning forever.
   */
  private void drain(String queueName) throws JMSException {
    for (int i = 0; i < 50; i++) {
      List<JmsMessageDto> messages = messageService.getAllMessages(queueName);
      if (messages.isEmpty()) {
        return;
      }
      for (JmsMessageDto message : messages) {
        messageService.deleteMessage(queueName, message.getMessageId());
      }
    }
    throw new IllegalStateException("could not drain " + queueName);
  }
}
