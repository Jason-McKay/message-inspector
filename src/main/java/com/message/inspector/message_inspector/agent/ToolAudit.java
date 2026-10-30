package com.message.inspector.message_inspector.agent;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Writes the one-line audit record for an MCP admin tool call:
 * <pre>
 * AUDIT agent-tool=delete_message user="admin" queue="DLQ" messageId="ID:..." outcome=success result="Message deleted successfully."
 * </pre>
 * {@code outcome} is one of {@code success}, {@code failed} (the service ran and reported
 * failure, including "Message not found."), {@code rejected} (refused before any broker
 * call: unknown queue or malformed ID) or {@code denied} (method security said no).
 * <p>
 * Every value except the tool name and outcome comes from the caller, so each is quoted,
 * escaped, stripped of control characters and capped in length. Without that, a message
 * ID containing a newline could forge a second audit line.
 */
final class ToolAudit {

  static final String SUCCESS = "success";
  static final String FAILED = "failed";
  static final String REJECTED = "rejected";
  static final String DENIED = "denied";

  private static final int MAX_VALUE_LENGTH = 256;

  private ToolAudit() {}

  /** Audit a call made by whoever is authenticated on the current thread. */
  static void record(String tool, String queueName, String messageId, String outcome, String result) {
    record(tool, currentUser(), queueName, messageId, outcome, result);
  }

  static void record(String tool, String user, String queueName, String messageId, String outcome, String result) {
    System.out.println("AUDIT agent-tool=" + tool
      + " user=" + quote(user)
      + " queue=" + quote(queueName)
      + " messageId=" + quote(messageId)
      + " outcome=" + outcome
      + " result=" + quote(result));
  }

  private static String currentUser() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    return authentication == null ? "anonymous" : authentication.getName();
  }

  /**
   * Also used for the tools' diagnostic lines: exception messages carry caller input
   * ({@code requireValidMessageId} echoes the rejected ID), so they could forge a line too.
   */
  static String quote(String value) {
    if (value == null) {
      return "\"\"";
    }
    String capped = value.length() > MAX_VALUE_LENGTH ? value.substring(0, MAX_VALUE_LENGTH) + "..." : value;
    String escaped = capped
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
      // Cc covers C0 and C1 controls (\n, \r, NEL); Zl/Zp are the Unicode line and
      // paragraph separators, which some log viewers also break on.
      .replaceAll("[\\p{Cc}\\p{Zl}\\p{Zp}]", "?");
    return "\"" + escaped + "\"";
  }
}
