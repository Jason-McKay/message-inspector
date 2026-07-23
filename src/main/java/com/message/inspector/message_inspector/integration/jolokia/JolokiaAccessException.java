package com.message.inspector.message_inspector.integration.jolokia;

/**
 * A failure talking to (or understanding) the broker's Jolokia endpoint. Its own type so
 * {@code ApiExceptionHandler} can answer 502 -- Jolokia is an upstream dependency like the
 * JMS connection, but a bare {@code RuntimeException} was indistinguishable from any other
 * server fault and surfaced as 500.
 */
public class JolokiaAccessException extends RuntimeException {

  public JolokiaAccessException(String message) {
    super(message);
  }

  public JolokiaAccessException(String message, Throwable cause) {
    super(message, cause);
  }
}
