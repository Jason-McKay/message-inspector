package com.message.inspector.message_inspector.integration.cache;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class RedisQueueRepositoryIntegrationTest {

  @Autowired
  private RedisQueueRepository redisQueueRepository;

  @Container
  static GenericContainer<?> redis = new GenericContainer<>("redis:6-alpine")
    .withExposedPorts(6379);

  @DynamicPropertySource
  static void redisProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.redis.host", redis::getHost);
    registry.add("spring.redis.port", redis::getFirstMappedPort);
  }


  @Test
  void shouldSaveAndRetrieveLastUsedQueue_withDefaultTTL() {
    String userId = "test-user-default";
    String queueName = "TestQueueDefault";

    redisQueueRepository.saveLastUsedQueue(userId, queueName);

    String retrieved = redisQueueRepository.getLastUsedQueue(userId);
    assertThat(retrieved).contains(queueName);
  }

  @Test
  void shouldSaveAndRetrieveLastUsedQueue_withCustomTTL() {
    String userId = "test-user-custom";
    String queueName = "TestQueueCustom";

    Duration ttl = Duration.ofSeconds(3); // short TTL
    redisQueueRepository.saveLastUsedQueue(userId, queueName, ttl);

    String retrievedImmediately = redisQueueRepository.getLastUsedQueue(userId);
    assertThat(retrievedImmediately).contains(queueName);

    // Wait for TTL to expire
    try {
      Thread.sleep(3500);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    String retrievedAfterExpiry = redisQueueRepository.getLastUsedQueue(userId);
    assertThat(retrievedAfterExpiry).isEmpty();
  }

  @Test
  void shouldDeleteLastUsedQueue() {
    String userId = "test-user-delete";
    String queueName = "ToDeleteQueue";

    redisQueueRepository.saveLastUsedQueue(userId, queueName);
    redisQueueRepository.deleteLastUsedQueue(userId);

    String result = redisQueueRepository.getLastUsedQueue(userId);
    assertThat(result).isEmpty();
  }
}
