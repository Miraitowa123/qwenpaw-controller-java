package com.qwenpaw.controller.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;

/**
 * 读取 Agent Gateway 写入 Redis 的用户访问心跳。
 */
@Slf4j
@Service
public class AgentHeartbeatService {

    private static final String HEARTBEAT_KEY_PREFIX = "agent:alive:";

    private final StringRedisTemplate redisTemplate;

    public AgentHeartbeatService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 返回用户最后使用时间和心跳键的实际剩余 TTL。
     */
    public HeartbeatStatus getStatus(String userId) {
        String key = HEARTBEAT_KEY_PREFIX + userId;
        try {
            String timestamp = redisTemplate.opsForValue().get(key);
            if (timestamp == null || timestamp.isBlank()) {
                return HeartbeatStatus.empty();
            }

            OffsetDateTime lastAccess = OffsetDateTime.ofInstant(
                    Instant.ofEpochMilli(Long.parseLong(timestamp)), ZoneOffset.UTC);
            Long ttlSeconds = redisTemplate.getExpire(key, TimeUnit.SECONDS);
            if (ttlSeconds == null || ttlSeconds < 0) {
                ttlSeconds = null;
            }
            return new HeartbeatStatus(lastAccess, ttlSeconds);
        } catch (NumberFormatException e) {
            log.warn("Invalid heartbeat timestamp for user {}", userId);
            return HeartbeatStatus.empty();
        } catch (RuntimeException e) {
            // Redis 临时不可用时不阻断管理台 Pod 列表。
            log.warn("Failed to read heartbeat for user {}: {}", userId, e.getMessage());
            return HeartbeatStatus.empty();
        }
    }

    public record HeartbeatStatus(OffsetDateTime lastAccess, Long ttlSeconds) {
        public static HeartbeatStatus empty() {
            return new HeartbeatStatus(null, null);
        }
    }
}
