package com.star.redis;


import io.lettuce.core.api.StatefulRedisConnection;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RedisConnections {

    private static final Map<Integer, StatefulRedisConnection<String, String>> REDIS_CONNECTION_MAP = new ConcurrentHashMap<>();

    public static StatefulRedisConnection<String, String> getConnection(int database) {
        return REDIS_CONNECTION_MAP.computeIfAbsent(database, RedisConnections::initConnection);
    }

    private static StatefulRedisConnection<String, String> initConnection(int database) {
        return RedisClientFactory.getClient(database).connect();
    }

}
