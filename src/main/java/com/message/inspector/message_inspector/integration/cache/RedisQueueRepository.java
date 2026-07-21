package com.message.inspector.message_inspector.integration.cache;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class RedisQueueRepository {

  private final RedisTemplate<String, String> redisTemplate;
  private static final String PREFIX = "lastUsedQueue:";
  private static final Duration DEFAULT_TTL = Duration.ofDays(7);

  public void saveLastUsedQueue(String userId, String queueName) {
    saveLastUsedQueue(userId, queueName, DEFAULT_TTL);
  }

  public void saveLastUsedQueue(String userId, String queueName, Duration ttl) {
    String key = buildKey(userId);
    redisTemplate.opsForValue().set(key, queueName, ttl);
    System.out.printf("Saved last used queue for userId=%s queueName=%s with TTL=%s", userId, queueName, ttl.toString());
  }

  public String getLastUsedQueue(String userId) {
    String key = buildKey(userId);
    String queueName = redisTemplate.opsForValue().get(key);
    if (queueName == null) {
      System.out.printf("No last used queue found for userId=%s", userId);
      return "";
    }
    System.out.printf("Retrieved last used queue for userId=%s queueName=%s", userId, queueName);
    return Optional.of(queueName).get();
  }

  public void deleteLastUsedQueue(String userId) {
    String key = buildKey(userId);
    Boolean deleted = redisTemplate.delete(key);
    if (deleted) {
      System.out.printf("Deleted last used queue for userId=%s", userId);
    } else {
      System.out.printf("No last used queue found to delete for userId=%s", userId);
    }
  }

  private String buildKey(String userId) {
    return PREFIX + userId;
  }
}
