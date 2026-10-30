package com.message.inspector.message_inspector.dto;

import lombok.Data;

import java.util.List;

/**
 * One page of a browse, as the MCP {@code browse_messages} tool returns it. {@code limit}
 * is the page size actually applied, which may differ from the one requested (it is
 * clamped), so an agent can page on with {@code offset + limit} while {@code hasMore}.
 */
@Data
public class AgentMessagePageDto {
  private String queueName;
  private int offset;
  private int limit;
  private int totalMessages;
  private boolean hasMore;
  private List<AgentMessageDto> messages;
}
