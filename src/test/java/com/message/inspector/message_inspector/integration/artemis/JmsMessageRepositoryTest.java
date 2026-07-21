package com.message.inspector.message_inspector.integration.artemis;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.message.inspector.message_inspector.model.JmsMessageDto;
import jakarta.jms.JMSException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.jms.core.BrowserCallback;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.core.SessionCallback;

/**
 * Guards the JMS selector-injection fix in {@link JmsMessageRepository}.
 * <p>
 * The three selector-building methods concatenate the message ID into a JMS selector,
 * so an ID containing a quote could widen the selector to match every message on the
 * queue — reading, consuming or DLQ-ing the wrong message while still reporting success.
 */
class JmsMessageRepositoryTest {

  public static final String QUEUE_NAME = "testQueue";
  public static final String VALID_MESSAGE_ID = "ID:6a3f9c2e-4b7d-11ee-be56-0242ac120002";

  /** Payloads that break out of the {@code JMSMessageID='...'} literal. */
  private static final String INJECTION_MATCH_ALL = "x' OR '1'='1";

  @Mock
  private JmsTemplate jmsTemplate;

  @InjectMocks
  private JmsMessageRepository jmsMessageRepository;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
  }

  /**
   * ✅ A legitimate Artemis ID is passed through, quoted exactly as before the fix.
   */
  @Test
  void testGetMessageById_ValidIdBuildsExpectedSelector() throws JMSException {
    JmsMessageDto expected = new JmsMessageDto(VALID_MESSAGE_ID, "Test Payload", QUEUE_NAME);
    when(jmsTemplate.browseSelected(anyString(), anyString(), ArgumentMatchers.<BrowserCallback<JmsMessageDto>>any()))
      .thenReturn(expected);

    JmsMessageDto actual = jmsMessageRepository.getMessageById(QUEUE_NAME, VALID_MESSAGE_ID);

    ArgumentCaptor<String> selector = ArgumentCaptor.forClass(String.class);
    verify(jmsTemplate).browseSelected(eq(QUEUE_NAME), selector.capture(), any());
    assertEquals("JMSMessageID='" + VALID_MESSAGE_ID + "'", selector.getValue());
    assertEquals(expected, actual);
  }

  /**
   * ❌ Injection payloads are rejected before the selector is built.
   */
  @ParameterizedTest
  @ValueSource(strings = {INJECTION_MATCH_ALL, "' OR '1'='1", "ID:abc' OR TRUE OR 'x'='", "ID:abc'", "a b", "ID:abc%00"})
  void testGetMessageById_RejectsInjection(String messageId) {
    assertThrows(IllegalArgumentException.class,
      () -> jmsMessageRepository.getMessageById(QUEUE_NAME, messageId));
    verifyNoInteractions(jmsTemplate);
  }

  @ParameterizedTest
  @ValueSource(strings = {INJECTION_MATCH_ALL, "ID:abc'"})
  void testDeleteMessageById_RejectsInjection(String messageId) {
    assertThrows(IllegalArgumentException.class,
      () -> jmsMessageRepository.deleteMessageById(QUEUE_NAME, messageId));
    verifyNoInteractions(jmsTemplate);
  }

  @ParameterizedTest
  @ValueSource(strings = {INJECTION_MATCH_ALL, "ID:abc'"})
  void testMoveToDeadLetterQueue_RejectsInjection(String messageId) {
    assertThrows(IllegalArgumentException.class,
      () -> jmsMessageRepository.moveToDeadLetterQueue(QUEUE_NAME, messageId));
    verifyNoInteractions(jmsTemplate);
  }

  /**
   * ❌ Null and empty IDs are rejected rather than producing an empty selector literal.
   */
  @ParameterizedTest
  @NullAndEmptySource
  void testGetMessageById_RejectsNullAndEmpty(String messageId) {
    assertThrows(IllegalArgumentException.class,
      () -> jmsMessageRepository.getMessageById(QUEUE_NAME, messageId));
    verifyNoInteractions(jmsTemplate);
  }

  /**
   * ✅ Valid IDs still reach the broker on the destructive paths.
   */
  @Test
  void testDeleteMessageById_ValidIdReachesBroker() throws JMSException {
    jmsMessageRepository.deleteMessageById(QUEUE_NAME, VALID_MESSAGE_ID);

    verify(jmsTemplate).execute(ArgumentMatchers.<SessionCallback<Object>>any(), eq(true));
  }
}
