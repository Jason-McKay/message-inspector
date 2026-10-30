# MCP demo: an agent triages the `orders` queue

A real, unedited run of Claude Code against the MCP server ([ADR 0009](adr/0009-mcp-server.md)).
The agent lists queues, browses one, and moves an invalid message to the DLQ. It is
recorded here because a green test suite cannot show how an agent actually uses the tool
descriptions.

- **Date:** 2026-10-30
- **Stack:** `docker compose up --build` (app, Artemis 2.31.2 and Redis)
- **Client:** Claude Code 2.1.314, headless (`claude -p`), model `claude-opus-5-5`
- **Identity:** a JWT for `admin`, from `POST /api/auth/login`. The token is redacted below.

## Setup

Three orders were put on `orders`. One of them, A-1002, has a negative quantity and total:

```bash
docker exec message-inspector-artemis-1 /opt/activemq-artemis/bin/artemis producer \
  --url tcp://localhost:61616 --destination queue://orders --message-count 1 \
  --user admin --password admin \
  --message '{"orderId":"A-1002","sku":"GADGET-3","qty":-5,"currency":"GBP","total":-74.95}'
# ...and the same for A-1001 and A-1003, which are valid
```

MCP config (`demo-mcp.json`):

```json
{
  "mcpServers": {
    "message-inspector": {
      "type": "http",
      "url": "http://localhost:8080/mcp",
      "headers": { "Authorization": "Bearer <redacted>" }
    }
  }
}
```

The command:
- loads only this server
- turns off Claude Code's built-in tools
- pre-approves the three read-only tools and `move_message_to_dlq`

`delete_message` and `resend_message` stay visible to the agent but are not pre-approved,
so in a headless run any call to them would be refused.

```bash
claude -p "Check the orders queue on the message broker. If any order message is clearly invalid (for example a negative quantity or total), move it to the DLQ - you have my approval for that, but don't delete or resend anything. Then tell me what you moved and what is left." \
  --mcp-config demo-mcp.json --strict-mcp-config --setting-sources "" --tools "" \
  --allowedTools "mcp__message-inspector__list_queues,mcp__message-inspector__browse_messages,mcp__message-inspector__get_message,mcp__message-inspector__move_message_to_dlq" \
  --output-format stream-json --verbose
```

## Transcript

This is rendered from the `stream-json` output. JSON results are pretty-printed. The second
browse is shortened to the fields that changed; nothing else is cut.

**→ `list_queues`** `{}`

```json
["orders", "DLQ", "ExpiryQueue", "activemq.notifications"]
```

**→ `browse_messages`** `{"queueName": "orders", "limit": 100}`

```json
{
  "queueName": "orders", "offset": 0, "limit": 100, "totalMessages": 3, "hasMore": false,
  "messages": [
    {
      "messageId": "ID:b44afbfa-d46e-11f1-9f24-06158d042acd", "queueName": "orders",
      "timestamp": "1793370736150",
      "properties": { "ThreadSent": "Producer ActiveMQQueue[orders], thread=0", "JMSXDeliveryCount": "0", "count": "0" },
      "payload": "{\"orderId\":\"A-1001\",\"sku\":\"WIDGET-7\",\"qty\":2,\"currency\":\"GBP\",\"total\":19.98}",
      "payloadLength": 76, "payloadTruncated": false
    },
    {
      "messageId": "ID:b554073a-d46e-11f1-819b-06158d042acd", "queueName": "orders",
      "timestamp": "1793370737887",
      "properties": { "ThreadSent": "Producer ActiveMQQueue[orders], thread=0", "JMSXDeliveryCount": "0", "count": "0" },
      "payload": "{\"orderId\":\"A-1002\",\"sku\":\"GADGET-3\",\"qty\":-5,\"currency\":\"GBP\",\"total\":-74.95}",
      "payloadLength": 78, "payloadTruncated": false
    },
    {
      "messageId": "ID:b6596933-d46e-11f1-bce7-06158d042acd", "queueName": "orders",
      "timestamp": "1793370739600",
      "properties": { "ThreadSent": "Producer ActiveMQQueue[orders], thread=0", "JMSXDeliveryCount": "0", "count": "0" },
      "payload": "{\"orderId\":\"A-1003\",\"sku\":\"WIDGET-7\",\"qty\":1,\"currency\":\"GBP\",\"total\":9.99}",
      "payloadLength": 75, "payloadTruncated": false
    }
  ]
}
```

> Only A-1002 is invalid (qty -5, total -74.95). I've already captured its properties above,
> since the DLQ copy drops them. Moving it now.

**→ `move_message_to_dlq`** `{"queueName": "orders", "messageId": "ID:b554073a-d46e-11f1-819b-06158d042acd"}`

```json
{ "success": true, "message": "Message moved to DLQ successfully." }
```

**→ `browse_messages`** `{"queueName": "orders"}`

```json
{
  "queueName": "orders", "offset": 0, "limit": 20, "totalMessages": 2, "hasMore": false,
  "messages": [
    { "messageId": "ID:b44afbfa-d46e-11f1-9f24-06158d042acd", "payload": "{\"orderId\":\"A-1001\", ...}", ... },
    { "messageId": "ID:b6596933-d46e-11f1-bce7-06158d042acd", "payload": "{\"orderId\":\"A-1003\", ...}", ... }
  ]
}
```

**Final answer:**

> I moved one of the three messages on the `orders` queue to the DLQ. I didn't delete or
> resend anything.
>
> **Moved to the DLQ:**
> - Order **A-1002** (`ID:b554073a-d46e-11f1-819b-06158d042acd`): SKU GADGET-3, qty **-5**,
>   total **-74.95 GBP**. Both the quantity and the total are negative.
>   - The broker gave the DLQ copy a new message ID and kept only the payload, so the
>     original properties were dropped. They were just default producer values:
>     `ThreadSent="Producer ActiveMQQueue[orders], thread=0"`, `JMSXDeliveryCount=0`,
>     `count=0`.
>
> **Left on `orders` (2 messages, both valid; I checked the queue again after the move):**
>
> | Order | SKU | Qty | Total |
> |---|---|---|---|
> | A-1001 | WIDGET-7 | 2 | 19.98 GBP |
> | A-1003 | WIDGET-7 | 1 | 9.99 GBP |
>
> In both remaining orders the total equals quantity × unit price (9.99 GBP).

5 turns, about 10 seconds.

## What the server recorded

The app container logged one audit line, for the one destructive call:

```
AUDIT agent-tool=move_message_to_dlq user="admin" queue="orders" messageId="ID:b554073a-d46e-11f1-819b-06158d042acd" outcome=success result="Message moved to DLQ successfully."
```

`browse_messages` on `DLQ` afterwards showed the A-1002 payload under a new ID
(`ID:c64f4209-...`), with only `JMSXDeliveryCount` left in its properties.

## What it shows

- **It read before it wrote.** The tool descriptions say a moved copy keeps only the payload
  and a new ID. The agent read the properties first and reported them, without being asked.
- **It used the IDs the server gave it.** It took the queue name from `list_queues` and the
  message ID from `browse_messages`. It never guessed an ID or a queue name.
- **It checked its work.** It browsed again after the move rather than trusting the
  `success` flag.
- **It ran under a real identity.** The audit line names the token's subject. With a
  USER token, the same `move_message_to_dlq` call gets an `isError` "Access Denied" result
  and an `outcome=denied` audit line. That was checked with MCP Inspector, and `McpServerIT`
  pins it.
