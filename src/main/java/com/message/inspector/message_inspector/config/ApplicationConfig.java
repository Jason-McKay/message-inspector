package com.message.inspector.message_inspector.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.support.converter.JacksonJsonMessageConverter;
import org.springframework.jms.support.converter.MessageConverter;
import org.springframework.jms.support.converter.MessageType;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class ApplicationConfig {

  /**
   * JMS JSON converter. HTTP JSON uses Boot's auto-configured {@link JsonMapper}
   * (Jackson 3, with {@code spring.jackson.use-jackson2-defaults} so the wire shape
   * stays aligned with the Jackson 2 mapper this API used to publish). This bean is
   * the same mapper, aimed at JMS text messages, replacing the Jackson 2
   * {@code MappingJackson2MessageConverter} Boot 4 no longer wants new code to use.
   */
  @Bean
  public MessageConverter jacksonJmsMessageConverter(JsonMapper jsonMapper) {
    JacksonJsonMessageConverter converter = new JacksonJsonMessageConverter(jsonMapper);
    converter.setTargetType(MessageType.TEXT); // Convert JMS messages to JSON text
    return converter;
  }
}
