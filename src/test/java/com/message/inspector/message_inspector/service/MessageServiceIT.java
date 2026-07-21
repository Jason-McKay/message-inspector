package com.message.inspector.message_inspector.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.message.inspector.message_inspector.dto.JmsMessageDto;
import com.message.inspector.message_inspector.integration.artemis.JmsMessageRepository;
import jakarta.jms.JMSException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import java.util.List;

@SpringBootTest
@TestPropertySource(locations = "classpath:application-test.properties")
class MessageServiceIT {

  @Autowired
  private JmsMessageRepository jmsMessageRepository;

  @Autowired
  private MessageService messageService;

  private static final String TEST_QUEUE = "testQueue";
  private String messageId;

  @BeforeEach
  void setup() throws JMSException {
    // Send a test message
    //JmsMessageDto testMessage = new JmsMessageDto("123", "Test Payload", TEST_QUEUE);

    // Send a message, creating the queue if it doesn't exist
    //jmsRepository.sendMessage(TEST_QUEUE, testMessage);

    // Retrieve the message ID after sending
    List<JmsMessageDto> messages = messageService.getAllMessages(TEST_QUEUE);
    assertThat(messages).isNotEmpty();
    messageId = messages.getFirst().getMessageId();

    System.out.println("The message id : " + messageId);
  }

  @Test
  void testGetAllMessages() throws JMSException {
    List<JmsMessageDto> messages = messageService.getAllMessages(TEST_QUEUE);
    assertThat(messages).isNotEmpty();
  }

  @Test
  void testGetMessageById() throws JMSException {
    JmsMessageDto message = messageService.getMessageById(TEST_QUEUE, messageId);
    assertThat(message).isNotNull();
    assertThat(message.getPayload()).isEqualTo("\"Test Payload\"");
  }

  @Test
  void testDeleteMessage() throws JMSException {
    messageService.deleteMessage(TEST_QUEUE, messageId);
    JmsMessageDto deletedMessage = messageService.getMessageById(TEST_QUEUE, messageId);
    assertThat(deletedMessage).isNull();
  }

  @Test
  void testMoveMessageToDLQ() throws JMSException {
    messageService.moveMessageToDLQ(TEST_QUEUE, messageId);
    List<JmsMessageDto> dlqMessages = messageService.getAllMessages(JmsMessageRepository.DLQ_NAME);
    assertThat(dlqMessages).isNotEmpty();
  }
}
