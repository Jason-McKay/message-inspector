package com.message.inspector.message_inspector.integration.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class RedisQueueRepositoryTest {

  @Container
  private static final GenericContainer<?> redisContainer = new GenericContainer<>(DockerImageName.parse("redis:6-alpine"))
    .withExposedPorts(6379);

  private RedisQueueRepository redisQueueRepository;
  private RedisTemplate<String, String> redisTemplate;

  @BeforeEach
  void setUp() {
    RedisStandaloneConfiguration redisConfig = new RedisStandaloneConfiguration();
    redisConfig.setHostName(redisContainer.getHost());
    redisConfig.setPort(redisContainer.getMappedPort(6379));

    LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(redisConfig);
    connectionFactory.afterPropertiesSet();

    redisTemplate = new RedisTemplate<>();
    redisTemplate.setConnectionFactory(connectionFactory);
    redisTemplate.setKeySerializer(new StringRedisSerializer());
    redisTemplate.setValueSerializer(new StringRedisSerializer());
    redisTemplate.afterPropertiesSet();

    redisQueueRepository = new RedisQueueRepository(redisTemplate);
  }

  @Test
  void shouldSaveAndRetrieveQueuePreference() {
    // Given
    String key = "user:123";
    String value = "test-queue";

    // When
    redisQueueRepository.saveLastUsedQueue(key, value);
    String retrieved = redisQueueRepository.getLastUsedQueue(key);

    // Then
    assertThat(retrieved).isEqualTo(value);
  }

  @Test
  void shouldSaveWithExpirationAndRetrieveQueuePreference() throws InterruptedException {
    // Given
    String key = "user:456";
    String value = "test-queue-ttl";
    Duration ttl = Duration.ofSeconds(2);

    // When
    redisQueueRepository.saveLastUsedQueue(key, value, ttl);
    String retrievedBefore = redisQueueRepository.getLastUsedQueue(key);

    // Then
    assertThat(retrievedBefore).isEqualTo(value);

    // Wait for expiration
    TimeUnit.SECONDS.sleep(3);

    // When
    String retrievedAfter = redisQueueRepository.getLastUsedQueue(key);

    // Then
    assertThat(retrievedAfter).isEmpty();
  }

  @Test
  void shouldReturnNullForNonExistentKey() {
    // Given
    String nonExistentKey = "user:non-existent";

    // When
    String retrieved = redisQueueRepository.getLastUsedQueue(nonExistentKey);

    // Then
    assertThat(retrieved).isEmpty();
  }

  @Test
  void shouldUpdateExistingValue() {
    // Given
    String key = "user:789";
    String initialValue = "initial-queue";
    String updatedValue = "updated-queue";

    // When
    redisQueueRepository.saveLastUsedQueue(key, initialValue);
    redisQueueRepository.saveLastUsedQueue(key, updatedValue);
    String retrieved = redisQueueRepository.getLastUsedQueue(key);

    // Then
    assertThat(retrieved).isEqualTo(updatedValue);
  }

  @Test
  void shouldDeleteKey() {
    // Given
    String key = "user:to-delete";
    String value = "queue-to-delete";
    redisQueueRepository.saveLastUsedQueue(key, value);

    // When
    redisQueueRepository.deleteLastUsedQueue(key);
    String retrieved = redisQueueRepository.getLastUsedQueue(key);

    // Then
    assertThat(retrieved).isEmpty();
  }
}
