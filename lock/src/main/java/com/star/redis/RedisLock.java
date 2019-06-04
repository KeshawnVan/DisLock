package com.star.redis;


import com.star.util.CastUtil;
import com.star.util.StreamUtil;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Redis distributed lock implementation.
 */
public class RedisLock implements Lock {

    private static final String OK = "OK";

    private static final String LOCK_PREFIX = "lock:";

    private static final String UNLOCK_SCRIPT = StreamUtil.getString(RedisLock.class.getClassLoader().getResourceAsStream("lua/redis_lock_unlock_script.lua"));

    /**
     * Lock key path.
     */
    private String lockKey;

    /**
     * 锁超时时间，防止线程在入锁以后，无限的执行等待
     */
    private int expireMsecs = 5 * 60 * 1000;

    /**
     * 锁等待时间，防止线程饥饿
     */
    private int timeoutMsecs = 1000;

    private boolean locked = false;

    private String value;

    public RedisLock(String lockKey) {
        this.lockKey = LOCK_PREFIX + lockKey;
    }

    public RedisLock(String lockKey, int timeoutMsecs) {
        this(lockKey);
        this.timeoutMsecs = timeoutMsecs;
    }

    public RedisLock(String lockKey, int timeoutMsecs, int expireMsecs) {
        this(lockKey, timeoutMsecs);
        this.expireMsecs = expireMsecs;
    }

    public String getLockKey() {
        return lockKey;
    }

    public boolean isLock() {
        return RedisConnections.getConnection(6).sync().exists(lockKey) > 0;
    }

    public boolean lock() {
        long waitMillis = timeoutMsecs;
        value = UUID.randomUUID().toString();
        RedisCommands<String, String> redisCommands = RedisConnections.getConnection(6).sync();
        while (waitMillis >= 0) {
            long startNanoTime = System.nanoTime();
            String lockResult = redisCommands.set(lockKey, value, SetArgs.Builder.nx().px(expireMsecs));
            locked = OK.equals(lockResult);
            if (locked || waitMillis == 0) return locked;
            int sleepMillis = new Random().nextInt(100);
            sleep(sleepMillis);
            long escapedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanoTime);
            waitMillis = waitMillis - escapedMillis;
        }
        return false;
    }

    private void sleep(int sleepMillis) {
        try {
            Thread.sleep(sleepMillis);
        } catch (InterruptedException e) {
            throw new RuntimeException(" Redis lock InterruptedException", e);
        }
    }

    public void unlock() {
        if (!locked) return;
        RedisCommands<String, String> redisCommands = RedisConnections.getConnection(6).sync();
        Object result = redisCommands.eval(UNLOCK_SCRIPT, ScriptOutputType.INTEGER, new String[]{lockKey}, value);
        if (CastUtil.castInt(result) < 0) {
        }
        locked = false;
    }

    @Override
    public void close() {
        unlock();
    }
}