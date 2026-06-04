package com.qwenpaw.controller.service;

import com.qwenpaw.controller.config.QwenPawProperties;
import com.qwenpaw.controller.model.PodStatus;
import com.qwenpaw.controller.model.UserPodMapping;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 从业务用户视角编排 QwenPaw Pod 的创建、查询、删除和重启。
 */
@Component
public class PodManager {

    /**
     * Pod 生命周期操作日志。
     */
    private static final Logger log = LoggerFactory.getLogger(PodManager.class);

    /**
     * 负责实际访问 Kubernetes API 的服务。
     */
    private final KubernetesService kubernetesService;

    /**
     * qwenpaw.* 配置项。
     */
    private final QwenPawProperties properties;

    /**
     * 注入 Kubernetes 操作服务和配置对象。
     */
    public PodManager(KubernetesService kubernetesService, QwenPawProperties properties) {
        this.kubernetesService = kubernetesService;
        this.properties = properties;
    }

    /**
     * 应用启动后按配置决定是否扫描 Kubernetes 中已有的用户 Pod。
     */
    @PostConstruct
    public void initialize() {
        if (!properties.isStartupSyncEnabled()) {
            log.info("Startup Kubernetes sync is disabled");
            return;
        }
        try {
            syncMappings();
        } catch (RuntimeException e) {
            log.warn("Startup Kubernetes sync failed; continuing application startup", e);
        }
    }

    /**
     * 获取用户 Pod；不存在时创建，异常状态可重启时尝试恢复。
     */
    public Optional<UserPodMapping> getOrCreateUserPod(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }

        // 先按标签查找已有 Pod，避免重复创建同一个用户的 Deployment。
        Optional<UserPodMapping> existing = findUserPod(userId);
        if (existing.isPresent()) {
            UserPodMapping mapping = existing.get();
            mapping.setLastAccess(now());
            mapping.setUpdatedAt(now());
            if (mapping.getStatus() == PodStatus.RUNNING) {
                return Optional.of(mapping);
            }
            if ((mapping.getStatus() == PodStatus.PENDING || mapping.getStatus() == PodStatus.CREATING)
                    && kubernetesService.waitForPodReady(mapping.getDeploymentName())) {
                mapping.setStatus(PodStatus.RUNNING);
                return Optional.of(mapping);
            }
            if (mapping.getStatus().canRestart() && restartUserPod(mapping)) {
                return Optional.of(mapping);
            }
        }

        return createUserPod(userId);
    }

    /**
     * 查询用户 Pod，并用 Kubernetes 当前状态刷新映射对象。
     */
    public Optional<UserPodMapping> getUserPod(String userId) {
        return findUserPod(userId)
                .map(mapping -> {
                    mapping.setStatus(kubernetesService.getPodStatus(mapping.getPodName()));
                    return mapping;
                });
    }

    /**
     * 列出所有带有 qwenpaw app 标签和 user 标签的 Pod。
     */
    public List<UserPodMapping> listUserPods() {
        return kubernetesService.listPodsByLabel(Map.of("app", properties.getQwenpawAppLabel()))
                .stream()
                .filter(pod -> pod.getMetadata() != null
                        && pod.getMetadata().getLabels() != null
                        && pod.getMetadata().getLabels().containsKey("user"))
                .map(this::mappingFromPod)
                .toList();
    }

    /**
     * 删除指定用户的 HTTPRoute、Service 和 Deployment。
     */
    public boolean deleteUserPod(String userId) {
        Optional<UserPodMapping> mapping = findUserPod(userId);
        if (mapping.isEmpty()) {
            log.warn("User {} has no pod", userId);
            return false;
        }
        UserPodMapping pod = mapping.get();
        kubernetesService.deleteHttpRoute(pod.getHttprouteName());
        kubernetesService.deleteService(pod.getServiceName());
        kubernetesService.deleteDeployment(pod.getDeploymentName());
        log.info("Deleted pod resources for user {}", userId);
        return true;
    }

    /**
     * 根据用户 ID 重启用户 Pod。
     */
    public Optional<UserPodMapping> restartUserPod(String userId) {
        Optional<UserPodMapping> mapping = findUserPod(userId);
        if (mapping.isEmpty()) {
            log.warn("User {} has no pod", userId);
            return Optional.empty();
        }
        UserPodMapping pod = mapping.get();
        if (!restartUserPod(pod)) {
            return Optional.empty();
        }
        return Optional.of(pod);
    }

    /**
     * 扫描 Kubernetes 中已有 Pod 并输出同步日志；映射信息由当前 Pod 实时生成。
     */
    public void syncMappings() {
        List<UserPodMapping> mappings = listUserPods();
        mappings.forEach(mapping -> log.info("Synced user {} pod {} status {}",
                mapping.getUserId(), mapping.getPodName(), mapping.getStatus()));
    }

    /**
     * 清理已经没有用户 Pod 对应的 Service 和 HTTPRoute。
     */
    public void cleanupOrphanedResources() {
        // activeUsers 记录当前仍然有 Pod 的用户，后面用来判断资源是否孤儿。
        Set<String> activeUsers = new HashSet<>();
        for (UserPodMapping mapping : listUserPods()) {
            activeUsers.add(mapping.getUserId());
        }

        for (Service service : kubernetesService.listManagedServices()) {
            Map<String, String> labels = service.getMetadata().getLabels();
            String userId = labels == null ? null : labels.get("user");
            if (userId != null && !activeUsers.contains(userId)) {
                log.info("Deleting orphaned service {}", service.getMetadata().getName());
                kubernetesService.deleteService(service.getMetadata().getName());
            }
        }

        for (GenericKubernetesResource route : kubernetesService.listManagedHttpRoutes()) {
            Map<String, String> labels = route.getMetadata().getLabels();
            String userId = labels == null ? null : labels.get("user");
            if (userId != null && !activeUsers.contains(userId)) {
                log.info("Deleting orphaned HTTPRoute {}", route.getMetadata().getName());
                kubernetesService.deleteHttpRoute(route.getMetadata().getName());
            }
        }
    }

    /**
     * 读取指定用户 Pod 的日志。
     */
    public String getPodLogs(String userId, int tailLines) {
        return getUserPod(userId)
                .map(mapping -> kubernetesService.getPodLogs(mapping.getPodName(), tailLines))
                .orElse(null);
    }

    /**
     * 创建用户对应的 Deployment、Service 和 HTTPRoute。
     */
    private Optional<UserPodMapping> createUserPod(String userId) {
        // mapping 是返回给上层的资源视图，真实 Pod 名称会在 Deployment 拉起后补上。
        UserPodMapping mapping = newMapping(userId);
        mapping.setStatus(PodStatus.CREATING);

        try {
            // createDeployment 会把 initContainer、主容器和 NAS 挂载写进 Pod 模板。
            kubernetesService.createDeployment(userId);
            if (!kubernetesService.waitForPodReady(mapping.getDeploymentName())) {
                mapping.setStatus(PodStatus.FAILED);
                return Optional.empty();
            }

            // Deployment 创建 Pod 后，按标签找回真实 Pod 名称。
            List<Pod> pods = kubernetesService.listPodsByLabel(Map.of(
                    "app", properties.getQwenpawAppLabel(),
                    "user", userId));
            if (!pods.isEmpty()) {
                mapping.setPodName(pods.get(0).getMetadata().getName());
            }

            kubernetesService.createService(userId);
            kubernetesService.createHttpRoute(userId);
            mapping.setStatus(PodStatus.RUNNING);
            mapping.setUpdatedAt(now());
            return Optional.of(mapping);
        } catch (RuntimeException e) {
            log.error("Failed to create pod for user {}", userId, e);
            mapping.setStatus(PodStatus.FAILED);
            return Optional.empty();
        }
    }

    /**
     * 重启已有用户 Pod；这里不会重建 Deployment 模板，只删除当前 Pod 让 Deployment 自动拉起新 Pod。
     */
    private boolean restartUserPod(UserPodMapping mapping) {
        mapping.setStatus(PodStatus.RESTARTING);
        mapping.setUpdatedAt(now());
        if (!kubernetesService.restartPod(mapping.getPodName())) {
            return false;
        }
        if (!kubernetesService.waitForPodReady(mapping.getDeploymentName())) {
            mapping.setStatus(PodStatus.FAILED);
            return false;
        }
        if (kubernetesService.getService(mapping.getServiceName()) == null) {
            kubernetesService.createService(mapping.getUserId());
        }
        // HTTPRoute 创建方法本身会先查重，所以这里可用于补齐缺失路由。
        kubernetesService.createHttpRoute(mapping.getUserId());
        List<Pod> pods = kubernetesService.listPodsByLabel(Map.of(
                "app", properties.getQwenpawAppLabel(),
                "user", mapping.getUserId()));
        if (!pods.isEmpty()) {
            mapping.setPodName(pods.get(0).getMetadata().getName());
        }
        mapping.setStatus(PodStatus.RUNNING);
        mapping.setUpdatedAt(now());
        return true;
    }

    /**
     * 按 app 和 user 标签查找用户 Pod。
     */
    private Optional<UserPodMapping> findUserPod(String userId) {
        List<Pod> pods = kubernetesService.listPodsByLabel(Map.of(
                "app", properties.getQwenpawAppLabel(),
                "user", userId));
        if (pods.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(mappingFromPod(pods.get(0)));
    }

    /**
     * 把 Kubernetes Pod 对象转换成控制器内部的用户资源映射。
     */
    private UserPodMapping mappingFromPod(Pod pod) {
        String userId = pod.getMetadata().getLabels().get("user");
        UserPodMapping mapping = newMapping(userId);
        mapping.setPodName(pod.getMetadata().getName());
        mapping.setStatus(kubernetesService.getPodStatus(pod.getMetadata().getName()));
        if (pod.getMetadata().getCreationTimestamp() != null) {
            mapping.setCreatedAt(OffsetDateTime.parse(pod.getMetadata().getCreationTimestamp()));
        }
        mapping.setUpdatedAt(now());
        mapping.setLastAccess(now());
        return mapping;
    }

    /**
     * 基于用户 ID 生成 Deployment、Service、HTTPRoute 等资源名称。
     */
    private UserPodMapping newMapping(String userId) {
        // 当前实现让用户相关资源共享同一个 qwenpaw-{userId} 名称。
        String resourceName = "qwenpaw-" + userId;
        UserPodMapping mapping = new UserPodMapping();
        mapping.setUserId(userId);
        mapping.setPodName("");
        mapping.setDeploymentName(resourceName);
        mapping.setServiceName(resourceName);
        mapping.setHttprouteName(resourceName);
        mapping.setPathPrefix(normalizeBasePath(properties.getBasePathPrefix()) + "/" + userId);
        mapping.setCreatedAt(now());
        mapping.setUpdatedAt(now());
        mapping.setLastAccess(now());
        return mapping;
    }

    /**
     * 规范化访问路径前缀，确保拼接用户路径时不会出现重复斜杠。
     */
    private String normalizeBasePath(String basePathPrefix) {
        if (basePathPrefix == null || basePathPrefix.isBlank() || "/".equals(basePathPrefix)) {
            return "";
        }
        // normalized 始终以 / 开头，最后再去掉末尾多余的 /。
        String normalized = basePathPrefix.startsWith("/") ? basePathPrefix : "/" + basePathPrefix;
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    /**
     * 获取 UTC 当前时间，避免不同节点时区影响状态时间。
     */
    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
