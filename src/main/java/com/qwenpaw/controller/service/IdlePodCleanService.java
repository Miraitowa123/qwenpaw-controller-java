package com.qwenpaw.controller.service;

import com.qwenpaw.controller.model.UserPodMapping;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class IdlePodCleanService {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private PodManager podManager;
    @Autowired
    private StringRedisTemplate redisTemplate;

    // 每 5 分钟执行
    @Scheduled(fixedRate = 300_000)
    public void cleanupIdlePods() {
        List<UserPodMapping> pods = podManager.listUserPods();
        log.info("[{}] 开始清理空闲 Pod，当前 Pod 总数: {}", LocalDateTime.now().format(TIME_FORMATTER), pods.size());

        List<String> cleanedPodNames = new ArrayList<>();
        for (UserPodMapping pod : pods) {
            String userId = pod.getUserId();
            String key = "agent:alive:" + userId;
            // Redis 键不存在 = 超过 72 小时无请求
            if (Boolean.FALSE.equals(redisTemplate.hasKey(key))) {
                podManager.deleteUserPod(userId);
                cleanedPodNames.add(pod.getPodName());
            }
        }
        log.info("[{}] 空闲 Pod 清理完成，总数: {}，已清理: {}，未清理: {}，已清理 Pod: {}",
                LocalDateTime.now().format(TIME_FORMATTER), pods.size(),
                cleanedPodNames.size(), pods.size() - cleanedPodNames.size(),
                cleanedPodNames.isEmpty() ? "无" : String.join(", ", cleanedPodNames));
    }
}