package com.qwenpaw.controller.service;

import com.qwenpaw.controller.model.UserPodMapping;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Controller 启动完成后对存量 Pod 执行一次用户信息回填，不包含任何定时扫描。
 */
@Slf4j
@Component
public class UserProfileInitialBackfill {

    private static final String BACKFILL_LOCK_KEY = "qwenpaw:user-profile:initial-backfill-lock";
    private static final Duration BACKFILL_LOCK_TTL = Duration.ofMinutes(30);

    private final PodManager podManager;
    private final UserProfileCacheService userProfileCacheService;
    private final StringRedisTemplate redisTemplate;

    public UserProfileInitialBackfill(PodManager podManager,
                                      UserProfileCacheService userProfileCacheService,
                                      StringRedisTemplate redisTemplate) {
        this.podManager = podManager;
        this.userProfileCacheService = userProfileCacheService;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 多 Controller 副本中仅允许一个实例执行；锁自动过期，不启动周期任务。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void backfillExistingPodsOnce() {
        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(BACKFILL_LOCK_KEY, "running", BACKFILL_LOCK_TTL);
            if (!Boolean.TRUE.equals(acquired)) {
                log.info("Skipping initial user profile backfill because another controller owns the lock");
                return;
            }

            List<String> userIds = podManager.listUserPods().stream()
                    .map(UserPodMapping::getUserId)
                    .filter(userId -> userId != null && !userId.isBlank())
                    .distinct()
                    .toList();
            List<String> missingUserIds = userProfileCacheService.findMissingUserIds(userIds);
            log.info("Starting initial user profile backfill: pods={}, missing={}",
                    userIds.size(), missingUserIds.size());
            for (String userId : missingUserIds) {
                userProfileCacheService.cacheIfAbsent(userId);
            }
            log.info("Initial user profile backfill completed: attempted={}", missingUserIds.size());
        } catch (RuntimeException e) {
            // 回填是管理台展示增强，不能影响 Controller 已经就绪后的正常服务。
            log.warn("Initial user profile backfill failed: {}", e.getMessage());
        }
    }
}
