package com.message.inspector.message_inspector.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.message.inspector.message_inspector.dto.JmsMessageDto;
import com.message.inspector.message_inspector.integration.jolokia.JolokiaAccessException;
import com.message.inspector.message_inspector.service.MessageService;
import com.message.inspector.message_inspector.service.QueueService;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import jakarta.jms.JMSException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.jms.UncategorizedJmsException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

class QueueInspectionToolsTest {

  public static final String QUEUE_NAME = "testQueue";
  public static final String MESSAGE_ID = "ID:1";

  @Mock
  private QueueService queueService;

  @Mock
  private MessageService messageService;

  private final JsonMapper jsonMapper = JsonMapper.builder().build();

  private QueueInspectionTools tools;

  @BeforeEach
  void setUp() throws JMSException {
    MockitoAnnotations.openMocks(this);
    tools = new QueueInspectionTools(queueService, messageService, jsonMapper);
    when(queueService.fetchQueueNamesWithJolokia()).thenReturn(List.of(QUEUE_NAME, "DLQ"));
  }

  /**
   * ✅ list_queues returns the broker's queue names as a JSON array.
   */
  @Test
  void testListQueues() throws JMSException {
    CallToolResult result = tools.listQueues();

    assertFalse(result.isError());
    assertEquals("[\"testQueue\",\"DLQ\"]", text(result));
    verify(queueService).fetchQueueNamesWithJolokia();
  }

  /**
   * ❌ A Jolokia failure is reported as the broker being unavailable, without the
   * exception's own message (which describes the broker's internals).
   */
  @Test
  void testListQueues_BrokerUnavailable() throws JMSException {
    when(queueService.fetchQueueNamesWithJolokia())
      .thenThrow(new JolokiaAccessException("Failed to fetch queue details: http://internal-host:8161"));

    CallToolResult result = tools.listQueues();

    assertTrue(result.isError());
    assertEquals(ToolResults.BROKER_UNAVAILABLE, text(result));
  }

  /**
   * ✅ With no limit or offset, browse returns the first page at the default size and says
   * there is more.
   */
  @Test
  void testBrowseMessages_DefaultPage() throws JMSException {
    when(messageService.getAllMessages(QUEUE_NAME)).thenReturn(messages(25));

    JsonNode page = json(tools.browseMessages(QUEUE_NAME, null, null));

    assertEquals(QUEUE_NAME, page.get("queueName").asString());
    assertEquals(0, page.get("offset").asInt());
    assertEquals(QueueInspectionTools.DEFAULT_PAGE_SIZE, page.get("limit").asInt());
    assertEquals(25, page.get("totalMessages").asInt());
    assertTrue(page.get("hasMore").asBoolean());
    assertEquals(QueueInspectionTools.DEFAULT_PAGE_SIZE, page.get("messages").size());
    assertEquals("ID:0", page.get("messages").get(0).get("messageId").asString());
  }

  /**
   * ✅ An oversized limit is clamped to the maximum, and the page reports the limit it
   * actually applied.
   */
  @Test
  void testBrowseMessages_LimitClampedToMaximum() throws JMSException {
    when(messageService.getAllMessages(QUEUE_NAME)).thenReturn(messages(150));

    JsonNode page = json(tools.browseMessages(QUEUE_NAME, 1000, 0));

    assertEquals(QueueInspectionTools.MAX_PAGE_SIZE, page.get("limit").asInt());
    assertEquals(QueueInspectionTools.MAX_PAGE_SIZE, page.get("messages").size());
    assertTrue(page.get("hasMore").asBoolean());
  }

  /**
   * ✅ A zero or negative limit is clamped up to one message, not treated as "all".
   */
  @Test
  void testBrowseMessages_LimitClampedToOne() throws JMSException {
    when(messageService.getAllMessages(QUEUE_NAME)).thenReturn(messages(5));

    assertEquals(1, json(tools.browseMessages(QUEUE_NAME, 0, 0)).get("messages").size());
    assertEquals(1, json(tools.browseMessages(QUEUE_NAME, -5, 0)).get("messages").size());
  }

  /**
   * ✅ The last page holds the remainder and reports no more.
   */
  @Test
  void testBrowseMessages_LastPage() throws JMSException {
    when(messageService.getAllMessages(QUEUE_NAME)).thenReturn(messages(25));

    JsonNode page = json(tools.browseMessages(QUEUE_NAME, 20, 20));

    assertEquals(5, page.get("messages").size());
    assertEquals("ID:20", page.get("messages").get(0).get("messageId").asString());
    assertFalse(page.get("hasMore").asBoolean());
  }

  /**
   * ✅ An offset past the end is an empty page, not an error; a negative one starts at 0.
   */
  @Test
  void testBrowseMessages_OffsetOutOfRange() throws JMSException {
    when(messageService.getAllMessages(QUEUE_NAME)).thenReturn(messages(3));

    JsonNode pastEnd = json(tools.browseMessages(QUEUE_NAME, 20, 50));
    assertEquals(0, pastEnd.get("messages").size());
    assertEquals(3, pastEnd.get("offset").asInt());
    assertFalse(pastEnd.get("hasMore").asBoolean());

    JsonNode negative = json(tools.browseMessages(QUEUE_NAME, 20, -1));
    assertEquals(0, negative.get("offset").asInt());
    assertEquals(3, negative.get("messages").size());
  }

  /**
   * ✅ A long payload is cut to the preview length and flagged; a short one is untouched.
   */
  @Test
  void testBrowseMessages_PayloadPreviewTruncated() throws JMSException {
    String longPayload = "x".repeat(QueueInspectionTools.PREVIEW_PAYLOAD_CHARS + 100);
    when(messageService.getAllMessages(QUEUE_NAME)).thenReturn(List.of(
      new JmsMessageDto("ID:long", longPayload, QUEUE_NAME, "1", Map.of()),
      new JmsMessageDto("ID:short", "hello", QUEUE_NAME, "2", Map.of("k", "v"))));

    JsonNode messages = json(tools.browseMessages(QUEUE_NAME, null, null)).get("messages");

    JsonNode cut = messages.get(0);
    assertEquals(QueueInspectionTools.PREVIEW_PAYLOAD_CHARS, cut.get("payload").asString().length());
    assertEquals(longPayload.length(), cut.get("payloadLength").asInt());
    assertTrue(cut.get("payloadTruncated").asBoolean());

    JsonNode whole = messages.get(1);
    assertEquals("hello", whole.get("payload").asString());
    assertEquals(5, whole.get("payloadLength").asInt());
    assertFalse(whole.get("payloadTruncated").asBoolean());
    assertEquals("v", whole.get("properties").get("k").asString());
  }

  /**
   * ❌ A queue name that is not on the broker is refused before any JMS call, so a guessed
   * name cannot auto-create an address.
   */
  @Test
  void testBrowseMessages_UnknownQueue() {
    CallToolResult result = tools.browseMessages("tset_queue", null, null);

    assertTrue(result.isError());
    assertEquals(ToolResults.UNKNOWN_QUEUE, text(result));
    verifyNoInteractions(messageService);
  }

  /**
   * ❌ A missing queue name is refused the same way.
   */
  @Test
  void testBrowseMessages_NullQueue() {
    CallToolResult result = tools.browseMessages(null, null, null);

    assertTrue(result.isError());
    assertEquals(ToolResults.UNKNOWN_QUEUE, text(result));
    verifyNoInteractions(messageService);
  }

  /**
   * ❌ A non-text message makes the whole browse fail (as on REST); the agent is told why.
   */
  @Test
  void testBrowseMessages_UnreadableMessage() throws JMSException {
    when(messageService.getAllMessages(QUEUE_NAME)).thenThrow(new IllegalArgumentException("Unsupported message type."));

    CallToolResult result = tools.browseMessages(QUEUE_NAME, null, null);

    assertTrue(result.isError());
    assertEquals(ToolResults.UNREADABLE_QUEUE, text(result));
  }

  /**
   * ❌ Both forms of JMS failure -- the checked {@code JMSException} and the unchecked
   * {@code JmsException} that {@code JmsTemplate} rethrows it as -- are reported as the
   * broker being unavailable.
   */
  @Test
  void testBrowseMessages_BrokerUnavailable() throws JMSException {
    when(messageService.getAllMessages(QUEUE_NAME))
      .thenThrow(new JMSException("AMQ219007: Cannot connect"))
      .thenThrow(new UncategorizedJmsException("AMQ219007: Cannot connect"));

    assertEquals(ToolResults.BROKER_UNAVAILABLE, text(tools.browseMessages(QUEUE_NAME, null, null)));
    assertEquals(ToolResults.BROKER_UNAVAILABLE, text(tools.browseMessages(QUEUE_NAME, null, null)));
  }

  /**
   * ✅ get_message returns the message with its payload cut to the single-message limit.
   */
  @Test
  void testGetMessage() throws JMSException {
    String payload = "y".repeat(QueueInspectionTools.FULL_PAYLOAD_CHARS + 1);
    when(messageService.getMessageById(QUEUE_NAME, MESSAGE_ID))
      .thenReturn(new JmsMessageDto(MESSAGE_ID, payload, QUEUE_NAME, "1700000000000", Map.of()));

    CallToolResult result = tools.getMessage(QUEUE_NAME, MESSAGE_ID);

    assertFalse(result.isError());
    JsonNode message = json(result);
    assertEquals(MESSAGE_ID, message.get("messageId").asString());
    assertEquals("1700000000000", message.get("timestamp").asString());
    assertEquals(QueueInspectionTools.FULL_PAYLOAD_CHARS, message.get("payload").asString().length());
    assertEquals(payload.length(), message.get("payloadLength").asInt());
    assertTrue(message.get("payloadTruncated").asBoolean());
  }

  /**
   * ✅ A message that is not there is a plain answer, not an error.
   */
  @Test
  void testGetMessage_NotFound() throws JMSException {
    when(messageService.getMessageById(QUEUE_NAME, MESSAGE_ID)).thenReturn(null);

    CallToolResult result = tools.getMessage(QUEUE_NAME, MESSAGE_ID);

    assertFalse(result.isError());
    assertTrue(text(result).startsWith("No message with that ID"));
  }

  /**
   * ❌ A malformed ID is rejected without echoing it: the repository's guard puts the
   * offending input in its exception message, and that must not reach the agent.
   */
  @Test
  void testGetMessage_InvalidIdNotEchoed() throws JMSException {
    String injection = "x' OR '1'='1";
    when(messageService.getMessageById(QUEUE_NAME, injection))
      .thenThrow(new IllegalArgumentException("Invalid message ID: " + injection));

    CallToolResult result = tools.getMessage(QUEUE_NAME, injection);

    assertTrue(result.isError());
    assertEquals(ToolResults.INVALID_MESSAGE_ID, text(result));
    assertFalse(text(result).contains("OR '1'='1"));
  }

  /**
   * ❌ get_message refuses an unknown queue before any JMS call.
   */
  @Test
  void testGetMessage_UnknownQueue() {
    CallToolResult result = tools.getMessage("nope", MESSAGE_ID);

    assertTrue(result.isError());
    assertEquals(ToolResults.UNKNOWN_QUEUE, text(result));
    verifyNoInteractions(messageService);
  }

  /**
   * ✅ Truncation never ends on the first half of a surrogate pair, so an emoji at the
   * boundary is dropped whole rather than split into an invalid character.
   */
  @Test
  void testTruncate_DoesNotSplitSurrogatePair() {
    String text = "a".repeat(9) + "😀" + "b";

    String cut = QueueInspectionTools.truncate(text, 10);

    assertEquals("a".repeat(9), cut);
    assertFalse(Character.isHighSurrogate(cut.charAt(cut.length() - 1)));
    assertEquals(text, QueueInspectionTools.truncate(text, 12));
  }

  private static List<JmsMessageDto> messages(int count) {
    return IntStream.range(0, count)
      .mapToObj(i -> new JmsMessageDto("ID:" + i, "payload " + i, QUEUE_NAME, String.valueOf(i), Map.of()))
      .toList();
  }

  private static String text(CallToolResult result) {
    return ((TextContent) result.content().get(0)).text();
  }

  private JsonNode json(CallToolResult result) {
    assertFalse(result.isError());
    return jsonMapper.readTree(text(result));
  }
}
