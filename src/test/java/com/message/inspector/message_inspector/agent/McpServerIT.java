package com.message.inspector.message_inspector.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.message.inspector.message_inspector.dto.JmsMessageDto;
import com.message.inspector.message_inspector.integration.artemis.JmsMessageRepository;
import com.message.inspector.message_inspector.security.TokenService;
import com.message.inspector.message_inspector.service.MessageService;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import jakarta.jms.JMSException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The MCP server end to end: a real MCP client, over real HTTP, through the real JWT filter
 * chain, into the tools, the service layer and a real Artemis broker -- JMS for the messages
 * and Jolokia for the queue list.
 * <p>
 * What only this can show: that the bearer token is still on the request thread when the
 * tool runs (so method security and the audit line see the caller), that the scanner calls
 * the method-security proxy rather than the bare bean, and that the unknown-queue guard
 * really does stop a typo from auto-creating an address on the broker.
 * <p>
 * Like {@code MessageServiceIT} it runs only under {@code ./mvnw verify}, needs Docker, and
 * is self-contained: its own broker, its own seeded message, both queues drained first.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class McpServerIT {

  private static final String TEST_QUEUE = "testQueue";
  private static final String PAYLOAD = "Test Payload";

  /**
   * Same image as the dev stack. The console port is exposed as well as JMS because
   * list_queues goes through Jolokia; the wait is on the console coming up, not just the
   * JMS acceptor, so the first Jolokia call cannot race it.
   */
  @Container
  static final GenericContainer<?> ARTEMIS =
      new GenericContainer<>("apache/activemq-artemis:2.31.2")
        .withExposedPorts(61616, 8161)
        .withEnv("ARTEMIS_USER", "admin")
        .withEnv("ARTEMIS_PASSWORD", "admin")
        .withEnv("ANONYMOUS_LOGIN", "false")
        .waitingFor(Wait.forLogMessage(".*AMQ241004.*", 1).withStartupTimeout(Duration.ofMinutes(2)));

  @DynamicPropertySource
  static void brokerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.artemis.broker-url",
        () -> "tcp://" + ARTEMIS.getHost() + ":" + ARTEMIS.getMappedPort(61616));
    registry.add("spring.artemis.user", () -> "admin");
    registry.add("spring.artemis.password", () -> "admin");
    registry.add("jolokia.url",
        () -> "http://" + ARTEMIS.getHost() + ":" + ARTEMIS.getMappedPort(8161) + "/console/jolokia");
  }

  @LocalServerPort
  private int port;

  @Autowired
  private TokenService tokenService;

  @Autowired
  private JmsMessageRepository jmsMessageRepository;

  @Autowired
  private MessageService messageService;

  @Autowired
  private JsonMapper jsonMapper;

  @Value("${message-inspector.dlq-name:DLQ}")
  private String dlqName;

  private final List<McpSyncClient> clients = new ArrayList<>();

  private McpSyncClient user;
  private McpSyncClient admin;
  private String messageId;

  @BeforeEach
  void setUp() throws JMSException {
    drain(TEST_QUEUE);
    drain(dlqName);

    jmsMessageRepository.sendMessage(TEST_QUEUE, new JmsMessageDto(null, PAYLOAD, TEST_QUEUE));
    messageId = messageService.getAllMessages(TEST_QUEUE).getFirst().getMessageId();

    user = connect(tokenService.issue("jason", List.of("USER")));
    admin = connect(tokenService.issue("admin", List.of("ADMIN")));
  }

  @AfterEach
  void tearDown() {
    clients.forEach(McpSyncClient::closeGracefully);
    clients.clear();
  }

  /**
   * ❌ Without a token the endpoint answers 401 with a bearer challenge, and a real MCP
   * client cannot even initialize.
   */
  @Test
  void testUnauthenticatedCallIsRejected() throws Exception {
    HttpResponse<String> response = HttpClient.newHttpClient().send(
      HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
        .header("Content-Type", "application/json")
        .header("Accept", "application/json, text/event-stream")
        .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
        .build(),
      HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(
      value -> assertThat(value).startsWith("Bearer"));

    McpSyncClient anonymous = client(null);
    assertThatThrownBy(anonymous::initialize).isInstanceOf(RuntimeException.class);
  }

  /**
   * ✅ The six tools are advertised, and their hints separate the read-only ones from the
   * ones that change the broker.
   */
  @Test
  void testToolsAreListedWithHints() {
    Map<String, Tool> tools = new HashMap<>();
    user.listTools().tools().forEach(tool -> tools.put(tool.name(), tool));

    assertThat(tools).containsOnlyKeys("list_queues", "browse_messages", "get_message",
      "resend_message", "delete_message", "move_message_to_dlq");
    for (String name : List.of("list_queues", "browse_messages", "get_message")) {
      assertThat(tools.get(name).annotations().readOnlyHint()).as(name).isTrue();
      assertThat(tools.get(name).annotations().destructiveHint()).as(name).isFalse();
    }
    for (String name : List.of("delete_message", "move_message_to_dlq")) {
      assertThat(tools.get(name).annotations().readOnlyHint()).as(name).isFalse();
      assertThat(tools.get(name).annotations().destructiveHint()).as(name).isTrue();
    }
    assertThat(tools.get("resend_message").annotations().readOnlyHint()).isFalse();
    assertThat(tools.get("resend_message").annotations().idempotentHint()).isFalse();
  }

  /**
   * ✅ A USER can list queues, browse, and fetch a message by ID against the live broker.
   */
  @Test
  void testReadOnlyToolsAsUser() {
    JsonNode queues = json(call(user, "list_queues", Map.of()));
    assertThat(queues.valueStream().map(JsonNode::asString)).contains(TEST_QUEUE, dlqName);

    JsonNode page = json(call(user, "browse_messages", Map.of("queueName", TEST_QUEUE)));
    assertThat(page.get("totalMessages").asInt()).isEqualTo(1);
    assertThat(page.get("hasMore").asBoolean()).isFalse();
    JsonNode browsed = page.get("messages").get(0);
    assertThat(browsed.get("messageId").asString()).isEqualTo(messageId);
    assertThat(browsed.get("payload").asString()).isEqualTo(PAYLOAD);
    assertThat(browsed.get("payloadTruncated").asBoolean()).isFalse();

    JsonNode message = json(call(user, "get_message", Map.of("queueName", TEST_QUEUE, "messageId", messageId)));
    assertThat(message.get("messageId").asString()).isEqualTo(messageId);
    assertThat(message.get("queueName").asString()).isEqualTo(TEST_QUEUE);
  }

  /**
   * ❌ A USER's delete is refused by method security: an error result, the message still on
   * the broker, and a denied audit line naming the token's subject.
   */
  @Test
  void testUserIsDeniedDestructiveTool(CapturedOutput output) throws JMSException {
    CallToolResult result = call(user, "delete_message", Map.of("queueName", TEST_QUEUE, "messageId", messageId));

    assertThat(result.isError()).isTrue();
    assertThat(messageService.getAllMessages(TEST_QUEUE)).hasSize(1);
    assertThat(output.getOut()).contains("AUDIT agent-tool=delete_message user=\"jason\" queue=\"testQueue\" "
      + "messageId=\"" + messageId + "\" outcome=denied");
  }

  /**
   * ✅ An ADMIN moves the message to the DLQ: gone from the source, payload on the DLQ, and
   * the move audited under the admin's name.
   */
  @Test
  void testAdminMovesMessageToDlq(CapturedOutput output) throws JMSException {
    CallToolResult result = call(admin, "move_message_to_dlq", Map.of("queueName", TEST_QUEUE, "messageId", messageId));

    assertThat(result.isError()).isFalse();
    assertThat(json(result).get("success").asBoolean()).isTrue();
    assertThat(messageService.getAllMessages(TEST_QUEUE)).isEmpty();
    assertThat(messageService.getAllMessages(dlqName)).extracting(JmsMessageDto::getPayload).contains(PAYLOAD);
    assertThat(output.getOut()).contains("AUDIT agent-tool=move_message_to_dlq user=\"admin\" queue=\"testQueue\" "
      + "messageId=\"" + messageId + "\" outcome=success result=\"Message moved to DLQ successfully.\"");
  }

  /**
   * ✅ An ADMIN resends (the queue then holds two) and deletes the original (the copy
   * remains); a second delete of the same ID reports "Message not found." truthfully.
   */
  @Test
  void testAdminResendsAndDeletes() throws JMSException {
    assertThat(call(admin, "resend_message", Map.of("queueName", TEST_QUEUE, "messageId", messageId)).isError())
      .isFalse();
    assertThat(messageService.getAllMessages(TEST_QUEUE)).hasSize(2);

    assertThat(call(admin, "delete_message", Map.of("queueName", TEST_QUEUE, "messageId", messageId)).isError())
      .isFalse();
    assertThat(messageService.getAllMessages(TEST_QUEUE))
      .hasSize(1)
      .allSatisfy(message -> assertThat(message.getMessageId()).isNotEqualTo(messageId));

    CallToolResult again = call(admin, "delete_message", Map.of("queueName", TEST_QUEUE, "messageId", messageId));
    assertThat(again.isError()).isTrue();
    assertThat(json(again).get("message").asString()).isEqualTo("Message not found.");
  }

  /**
   * ❌ A mistyped queue name is refused before any JMS call, so -- unlike a REST browse --
   * it does not auto-create an address that then pollutes the queue list.
   */
  @Test
  void testUnknownQueueCreatesNoAddress() {
    CallToolResult result = call(user, "browse_messages", Map.of("queueName", "testQueue_typo"));

    assertThat(result.isError()).isTrue();
    assertThat(text(result)).isEqualTo(ToolResults.UNKNOWN_QUEUE);
    JsonNode queues = json(call(user, "list_queues", Map.of()));
    assertThat(queues.valueStream().map(JsonNode::asString)).doesNotContain("testQueue_typo");
  }

  private McpSyncClient connect(String token) {
    McpSyncClient client = client(token);
    client.initialize();
    return client;
  }

  private McpSyncClient client(String token) {
    HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
      .builder("http://localhost:" + port)
      .endpoint("/mcp")
      .httpRequestCustomizer((request, method, uri, body, context) -> {
        if (token != null) {
          request.header("Authorization", "Bearer " + token);
        }
      })
      .build();
    McpSyncClient client = McpClient.sync(transport)
      .requestTimeout(Duration.ofSeconds(30))
      .clientInfo(new Implementation("mcp-server-it", "1.0.0"))
      .build();
    clients.add(client);
    return client;
  }

  private static CallToolResult call(McpSyncClient client, String tool, Map<String, Object> arguments) {
    return client.callTool(new CallToolRequest(tool, arguments));
  }

  private static String text(CallToolResult result) {
    return ((TextContent) result.content().getFirst()).text();
  }

  private JsonNode json(CallToolResult result) {
    return jsonMapper.readTree(text(result));
  }

  /** Same as {@code MessageServiceIT}: drain through the application's own paths, capped. */
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
