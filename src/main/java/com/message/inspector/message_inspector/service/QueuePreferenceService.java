package com.message.inspector.message_inspector.service;

import com.message.inspector.message_inspector.repository.RedisQueueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class QueuePreferenceService {

  private final RedisQueueRepository redisQueueRepository;

  public void saveLastUsedQueue(String userId, String queueName) {
    redisQueueRepository.saveLastUsedQueue(userId, queueName);
  }

  public void saveLastUsedQueue(String userId, String queueName, Duration timeToLive) {
    redisQueueRepository.saveLastUsedQueue(userId, queueName, timeToLive);
  }

  public String getLastUsedQueue(String userId) {
    return redisQueueRepository.getLastUsedQueue(userId);
  }
}
