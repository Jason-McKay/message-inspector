package com.message.inspector.message_inspector.config;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.Session;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.annotation.EnableJms;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.connection.CachingConnectionFactory;
import org.springframework.jms.core.JmsTemplate;

@Configuration
@EnableJms
public class ArtemisConfig {

  // Read from properties rather than baked in so that a test (or any non-local
  // deployment) can point the JmsTemplate at a different broker -- previously
  // impossible, which is why MessageServiceIT could only ever run against localhost.
  // The URL and user default to the local stack in ../infrastructure. The password
  // deliberately has no default (since 2026-11-10): a missing ARTEMIS_PASSWORD fails
  // startup instead of silently trying admin. ArtemisCredentialsConfigTest pins this.
  @Value("${spring.artemis.broker-url:tcp://localhost:61616}")
  private String brokerUrl;

  @Value("${spring.artemis.user:admin}")
  private String username;

  @Value("${spring.artemis.password}")
  private String password;

  @Bean
  public ConnectionFactory connectionFactory() {
    return new ActiveMQConnectionFactory(brokerUrl, username, password);
  }

  @Bean
  public DefaultJmsListenerContainerFactory jmsListenerContainerFactory(ConnectionFactory connectionFactory) {
    DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();
    factory.setConnectionFactory(connectionFactory);
    factory.setConcurrency("1-5"); // Min 1, Max 5 consumers
    return factory;
  }

  @Bean
  public CachingConnectionFactory cachingConnectionFactory(ConnectionFactory connectionFactory) {
    CachingConnectionFactory cachingConnectionFactory = new CachingConnectionFactory(connectionFactory);
    cachingConnectionFactory.setSessionCacheSize(10); // Improve performance
    cachingConnectionFactory.setReconnectOnException(true);
    return cachingConnectionFactory;
  }

  @Bean
  public JmsTemplate jmsTemplate(CachingConnectionFactory cachingConnectionFactory) {
    JmsTemplate jmsTemplate = new JmsTemplate(cachingConnectionFactory);
    jmsTemplate.setSessionAcknowledgeMode(Session.CLIENT_ACKNOWLEDGE); // ✅ Ensure acknowledgment mode
    return jmsTemplate;
  }
}
