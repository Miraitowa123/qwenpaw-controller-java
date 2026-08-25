package com.qwenpaw.controller.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwenpaw.controller.model.UserProfile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 用户展示信息的 Redis 缓存。正常缓存不设 TTL，列表查询永远不会访问用户中心。
 */
@Slf4j
@Service
public class UserProfileCacheService {

    private static final String CACHE_KEY_PREFIX = "qwenpaw:user-profile:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final UserCenterClient userCenterClient;

    public UserProfileCacheService(StringRedisTemplate redisTemplate,
                                   ObjectMapper objectMapper,
                                   UserCenterClient userCenterClient) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.userCenterClient = userCenterClient;
    }

    /**
     * 创建 Pod 或存量回填时调用：缓存命中直接返回，只有键不存在才查询用户中心。
     */
    public Optional<UserProfile> cacheIfAbsent(String userId) {
        String key = cacheKey(userId);
        final String cachedJson;
        try {
            cachedJson = redisTemplate.opsForValue().get(key);
        } catch (RuntimeException e) {
            // Redis 不可用时无法满足“查询一次并缓存”，因此不降级直连用户中心。
            log.warn("Failed to read user profile cache for user {}: {}", userId, e.getMessage());
            return Optional.empty();
        }

        if (cachedJson != null) {
            return parseProfile(userId, cachedJson);
        }

        try {
            UserProfile profile = userCenterClient.queryUserProfile(userId);
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(profile));
            return Optional.of(profile);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while querying user profile for user {}", userId);
        } catch (IOException | RuntimeException e) {
            // 用户展示信息不是 Pod 创建的必要条件，查询失败不能阻断主流程。
            log.warn("Failed to cache user profile for user {}: {}", userId, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * 管理台列表使用一次 MGET 批量读取当前页缓存；此方法不会查询用户中心。
     */
    public Map<String, UserProfile> getCachedProfiles(Collection<String> userIds) {
        List<String> normalizedUserIds = userIds.stream()
                .filter(userId -> userId != null && !userId.isBlank())
                .distinct()
                .toList();
        if (normalizedUserIds.isEmpty()) {
            return Map.of();
        }

        List<String> keys = normalizedUserIds.stream().map(this::cacheKey).toList();
        try {
            List<String> values = redisTemplate.opsForValue().multiGet(keys);
            if (values == null) {
                return Map.of();
            }
            Map<String, UserProfile> profiles = new LinkedHashMap<>();
            for (int i = 0; i < Math.min(normalizedUserIds.size(), values.size()); i++) {
                String value = values.get(i);
                if (value == null) {
                    continue;
                }
                String userId = normalizedUserIds.get(i);
                parseProfile(userId, value)
                        .ifPresent(profile -> profiles.put(userId, profile));
            }
            return profiles;
        } catch (RuntimeException e) {
            log.warn("Failed to read user profile caches: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * 返回当前没有有效缓存的用户 ID，供一次性存量回填使用。
     */
    public List<String> findMissingUserIds(Collection<String> userIds) {
        List<String> distinctUserIds = new ArrayList<>(userIds.stream().distinct().toList());
        Map<String, UserProfile> cachedProfiles = getCachedProfiles(distinctUserIds);
        return distinctUserIds.stream().filter(userId -> !cachedProfiles.containsKey(userId)).toList();
    }

    private Optional<UserProfile> parseProfile(String userId, String json) {
        try {
            return Optional.of(objectMapper.readValue(json, UserProfile.class));
        } catch (JsonProcessingException e) {
            log.warn("Invalid user profile cache for user {}: {}", userId, e.getMessage());
            return Optional.empty();
        }
    }

    private String cacheKey(String userId) {
        return CACHE_KEY_PREFIX + userId;
    }
}
