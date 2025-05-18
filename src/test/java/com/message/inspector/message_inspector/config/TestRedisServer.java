package com.message.inspector.message_inspector.config;

import redis.embedded.RedisServer;

import java.io.File;
import java.io.IOException;

public class TestRedisServer {

  private static RedisServer redisServer;

  public static void start() throws IOException {
    if (redisServer == null) {
      redisServer = new RedisServer(new File("localhost"), 6379); // 👈 path to your local redis-server
    }
    redisServer.start();
  }

  public static void stop() {
    if (redisServer != null && redisServer.isActive()) {
      redisServer.stop();
    }
  }
}
