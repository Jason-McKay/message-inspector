package com.message.inspector.message_inspector.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.message.inspector.message_inspector.model.ApiResponse;
import com.message.inspector.message_inspector.model.JmsMessageDto;
import com.message.inspector.message_inspector.integration.artemis.JmsMessageRepository;
import jakarta.jms.JMSException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import java.util.List;

class MessageServiceTest {

  public static final String MESSAGE_ID = "123";
  public static final String QUEUE_NAME = "testQueue";
  @Mock
  private JmsMessageRepository jmsMessageRepository;

  @InjectMocks
  private MessageService messageService;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
  }

  /**
   * ✅ Test fetching all messages from a queue.
   */
  @Test
  void testGetAllMessages() throws JMSException {
    List<JmsMessageDto> mockMessages = List.of(new JmsMessageDto(MESSAGE_ID, "Test Message", QUEUE_NAME));
    when(jmsMessageRepository.getMessagesFromQueue(QUEUE_NAME)).thenReturn(mockMessages);

    List<JmsMessageDto> messages = messageService.getAllMessages(QUEUE_NAME);

    assertEquals(1, messages.size());
    assertEquals("Test Message", messages.get(0).getPayload());
    verify(jmsMessageRepository, times(1)).getMessagesFromQueue(QUEUE_NAME);
  }

  @Test
  void testGetAllMessages_EmptyQueue() throws JMSException {
    when(jmsMessageRepository.getMessagesFromQueue("emptyQueue")).thenReturn(List.of());

    List<JmsMessageDto> messages = messageService.getAllMessages("emptyQueue");

    assertTrue(messages.isEmpty());
    verify(jmsMessageRepository).getMessagesFromQueue("emptyQueue");
  }

  @Test
  void testGetAllMessages_JMSException() throws JMSException {
    when(jmsMessageRepository.getMessagesFromQueue("failQueue")).thenThrow(new JMSException("JMS error"));

    assertThrows(JMSException.class, () -> messageService.getAllMessages("failQueue"));
  }

  /**
   * ✅ Test fetching a specific message by ID.
   */
  @Test
  void testGetMessageById() throws JMSException {
    JmsMessageDto mockMessage = new JmsMessageDto(MESSAGE_ID, "Test Payload", QUEUE_NAME);
    when(jmsMessageRepository.getMessageById(QUEUE_NAME, MESSAGE_ID)).thenReturn(mockMessage);

    JmsMessageDto message = messageService.getMessageById(QUEUE_NAME, MESSAGE_ID);

    assertNotNull(message);
    assertEquals(MESSAGE_ID, message.getMessageId());
    assertEquals("Test Payload", message.getPayload());
    verify(jmsMessageRepository, times(1)).getMessageById(QUEUE_NAME, MESSAGE_ID);
  }

  @Test
  void testGetMessageById_NotFound() throws JMSException {
    when(jmsMessageRepository.getMessageById(QUEUE_NAME, "missing-id")).thenReturn(null);

    JmsMessageDto result = messageService.getMessageById(QUEUE_NAME, "missing-id");

    assertNull(result);
  }

  @Test
  void testGetMessageById_JMSException() throws JMSException {
    when(jmsMessageRepository.getMessageById(QUEUE_NAME, "bad-id")).thenThrow(new JMSException("JMS error"));

    assertThrows(JMSException.class, () -> messageService.getMessageById(QUEUE_NAME, "bad-id"));
  }

  /**
   * ✅ Test resending a message.
   */
  @Test
  void testResendMessage() throws JMSException {
    JmsMessageDto mockMessage = new JmsMessageDto(MESSAGE_ID, "Test Payload", QUEUE_NAME);
    when(jmsMessageRepository.getMessageById(QUEUE_NAME, MESSAGE_ID)).thenReturn(mockMessage);

    assertDoesNotThrow(() -> messageService.resendMessage(QUEUE_NAME, MESSAGE_ID));
    verify(jmsMessageRepository, times(1)).sendMessage(QUEUE_NAME, mockMessage);
  }

  @Test
  void testResendMessage_MessageNotFound() throws JMSException {
    when(jmsMessageRepository.getMessageById(QUEUE_NAME, "unknown")).thenReturn(null);

    assertThrows(NullPointerException.class, () -> messageService.resendMessage(QUEUE_NAME, "unknown"));
  }

  /**
   * ✅ Test deleting a message.
   */
  @Test
  void testDeleteMessage() throws JMSException {
    assertDoesNotThrow(() -> messageService.deleteMessage(QUEUE_NAME, MESSAGE_ID));
    verify(jmsMessageRepository, times(1)).deleteMessageById(QUEUE_NAME, MESSAGE_ID);
  }

  @Test
  void testDeleteMessage_JMSException() throws JMSException {
    doThrow(new JMSException("Delete failed")).when(jmsMessageRepository).deleteMessageById(QUEUE_NAME, "bad-id");

    ApiResponse response = messageService.deleteMessage(QUEUE_NAME, "bad-id");

    assertFalse(response.isSuccess());
    assertTrue(response.getMessage().contains("Delete failed"));
  }

  /**
   * ✅ Test moving a message to the DLQ.
   */
  @Test
  void testMoveMessageToDLQ() throws JMSException {
    assertDoesNotThrow(() -> messageService.moveMessageToDLQ(QUEUE_NAME, MESSAGE_ID));
    verify(jmsMessageRepository, times(1)).moveToDeadLetterQueue(QUEUE_NAME, MESSAGE_ID);
  }

  @Test
  void testMoveMessageToDLQ_JMSException() throws JMSException {
    doThrow(new JMSException("Move failed")).when(jmsMessageRepository).moveToDeadLetterQueue(QUEUE_NAME, "bad-id");

    ApiResponse response = messageService.moveMessageToDLQ(QUEUE_NAME, "bad-id");

    assertFalse(response.isSuccess());
    assertTrue(response.getMessage().contains("Move failed"));
  }
}
