package com.message.inspector.message_inspector.controller;

import com.message.inspector.message_inspector.dto.ApiResponse;
import com.message.inspector.message_inspector.integration.jolokia.JolokiaAccessException;
import jakarta.jms.JMSException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates exceptions escaping the controllers into responses with a truthful status
 * code.
 * <p>
 * Before this existed, nothing handled them: the read paths declare
 * {@code throws JMSException} and simply let it out, the container forwarded to
 * {@code /error}, and -- because {@code /error} was not permitted by the security chain --
 * the caller received <strong>403</strong>. Every server-side failure was disguised as a
 * permission problem. Permitting {@code /error} fixes the masking; this class supplies
 * the statuses that actually describe what went wrong.
 * <p>
 * Responses reuse {@link ApiResponse} so failures look like the destructive endpoints'
 * existing failure shape rather than introducing a second error format.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

  /**
   * A malformed message ID -- the {@code SAFE_MESSAGE_ID} guard in JmsMessageRepository
   * rejecting selector-injection input -- is a bad request, not a server fault. The guard
   * is a security control, so the reason is deliberately not echoed back verbatim.
   */
  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ApiResponse> handleIllegalArgument(IllegalArgumentException e) {
    System.out.println("Rejected request: " + e.getMessage());
    return ResponseEntity.badRequest().body(response("Invalid request parameter."));
  }

  /**
   * The broker is an upstream dependency, so a JMS failure is 502 rather than 500: the
   * app is working, the system it depends on is not. This is what a browse against a
   * stopped broker now returns.
   */
  @ExceptionHandler(JMSException.class)
  public ResponseEntity<ApiResponse> handleJms(JMSException e) {
    System.out.println("Broker error: " + e.getMessage());
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
      .body(response("Broker unavailable: " + e.getMessage()));
  }

  /**
   * Jolokia is the broker's management face, so failing to reach or parse it is the same
   * class of fault as a JMS failure: an upstream problem, 502. Previously these arrived
   * as bare {@code RuntimeException}s and fell through to the container's 500.
   */
  @ExceptionHandler(JolokiaAccessException.class)
  public ResponseEntity<ApiResponse> handleJolokia(JolokiaAccessException e) {
    System.out.println("Jolokia error: " + e.getMessage());
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
      .body(response("Broker unavailable: " + e.getMessage()));
  }

  /**
   * Bad credentials at login. Thrown by the {@code AuthenticationManager} inside
   * {@code AuthController} (not by the resource-server filter, which handles missing/invalid
   * tokens itself), so it reaches this advice and must be answered 401 -- deliberately
   * without saying which of username or password was wrong.
   */
  @ExceptionHandler(AuthenticationException.class)
  public ResponseEntity<ApiResponse> handleAuthentication(AuthenticationException e) {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response("Invalid username or password."));
  }

  private ApiResponse response(String message) {
    ApiResponse apiResponse = new ApiResponse();
    apiResponse.setSuccess(false);
    apiResponse.setMessage(message);
    return apiResponse;
  }
}
