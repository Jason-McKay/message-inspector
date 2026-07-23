/**
 * Business logic — the layer between the controllers and the external systems.
 *
 * <p>Services orchestrate: a controller calls a service, the service calls one or more
 * {@code integration} adapters and decides what the result means. The one rule worth
 * internalising here is the <strong>response contract</strong>: the broker operations
 * (resend / delete / move-to-dlq) can fail without throwing — a missing message is a
 * successful call that did nothing. So a service must inspect what the repository actually
 * did and set {@code success} accordingly, rather than assuming "no exception" means "it
 * worked". A transport or broker fault is different and does throw, and is turned into a
 * truthful status by {@code controller/ApiExceptionHandler}.
 *
 * <p>{@code QueuePreferenceService} is the seam in front of the Redis-backed last-used-queue
 * store — see {@code integration/cache} and ADR 0003.
 */
package com.message.inspector.message_inspector.service;
