package com.message.inspector.message_inspector.agent;

import org.aopalliance.intercept.MethodInvocation;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.security.authorization.event.AuthorizationDeniedEvent;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

/**
 * Audits MCP tool calls that method security denies. Those never reach the tool method, so
 * {@link QueueAdminTools} cannot log them itself, yet "a USER tried to delete a message" is
 * exactly the line an audit trail exists for.
 * <p>
 * Spring Security publishes the denial as an {@link AuthorizationDeniedEvent} (it needs the
 * {@code AuthorizationEventPublisher} bean in {@code SecurityConfig} to do so). Denials of
 * anything other than an {@code @McpTool} method -- every 401 on the REST API publishes one
 * too -- are ignored, so nothing changes for REST.
 */
@Component
class AgentAuthorizationAuditListener {

  @EventListener
  public void onDenied(AuthorizationDeniedEvent<?> event) {
    if (!(event.getObject() instanceof MethodInvocation invocation)) {
      return;
    }
    Method method = invocation.getMethod();
    McpTool tool = AnnotationUtils.findAnnotation(method, McpTool.class);
    if (tool == null) {
      return;
    }
    Authentication authentication = event.getAuthentication().get();
    ToolAudit.record(tool.name(),
      authentication == null ? "anonymous" : authentication.getName(),
      argument(invocation, "queueName"),
      argument(invocation, "messageId"),
      ToolAudit.DENIED,
      "Access denied");
  }

  /** The named argument's value, matched by parameter name (the build compiles with -parameters). */
  private static String argument(MethodInvocation invocation, String name) {
    Parameter[] parameters = invocation.getMethod().getParameters();
    Object[] arguments = invocation.getArguments();
    for (int i = 0; i < parameters.length; i++) {
      if (parameters[i].getName().equals(name) && arguments[i] instanceof String value) {
        return value;
      }
    }
    return null;
  }
}
