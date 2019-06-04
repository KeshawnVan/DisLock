package com.star.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;

import java.time.Duration;


public class RedisClientFactory {


    public static RedisClient getClient(int db) {
        RedisURI redisURI = RedisURI.builder()
                .withHost("10.0.251.109")
                .withPort(6380)
                .withDatabase(db)
                .withTimeout(Duration.ofSeconds(30)).build();
        return RedisClient.create(redisURI);
    }

}
