/**
 * Spring wiring — beans and third-party configuration, not domain concerns.
 *
 * <p>These classes assemble the objects the rest of the app injects: {@code ArtemisConfig}
 * builds the JMS {@code ConnectionFactory} (reading {@code spring.artemis.*} so the broker
 * URL/credentials are externalised), {@code RedisConfig} the Redis template,
 * {@code RestTemplateConfig} the {@code jolokiaRestTemplate}, {@code ApplicationConfig} the
 * JMS JSON {@code MessageConverter} (HTTP JSON uses Boot's auto-configured {@code JsonMapper}),
 * and {@code SwaggerConfig} the OpenAPI bean.
 *
 * <p>Security wiring lives in its own {@code security} package rather than here, because it
 * is a cross-cutting concern with its own decisions (ADR 0005), not plumbing. Everything in
 * {@code config} is plumbing.
 *
 * <p>Note {@code ArtemisConfig} defines its own {@code ConnectionFactory}, which is why
 * {@code spring.main.allow-bean-definition-overriding=true} is set.
 */
package com.message.inspector.message_inspector.config;
