package com.message.inspector.message_inspector.config;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.Session;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.annotation.EnableJms;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.connection.CachingConnectionFactory;
import org.springframework.jms.core.JmsTemplate;

@Configuration
@EnableJms
public class ArtemisConfig {

  private static final String BROKER_URL = "tcp://localhost:61616";
  private static final String USERNAME = "admin";
  private static final String PASSWORD = "admin";

  @Bean
  public ConnectionFactory connectionFactory() {
    return new ActiveMQConnectionFactory(BROKER_URL, USERNAME, PASSWORD);
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
