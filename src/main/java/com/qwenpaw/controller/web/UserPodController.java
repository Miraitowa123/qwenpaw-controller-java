package com.qwenpaw.controller.web;

import com.qwenpaw.controller.config.QwenPawProperties;
import com.qwenpaw.controller.model.ConfigMapData;
import com.qwenpaw.controller.model.ListUserPodsResponse;
import com.qwenpaw.controller.model.UpdateConfigRequest;
import com.qwenpaw.controller.model.UpdateConfigResponse;
import com.qwenpaw.controller.model.UserPodMapping;
import com.qwenpaw.controller.model.UserPodResponse;
import com.qwenpaw.controller.service.KubernetesService;
import com.qwenpaw.controller.service.PodManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 用户 Pod 和管理台配置相关的 REST API。
 */
@RestController
@RequestMapping("/api/v1")
public class UserPodController {

    /**
     * 接口访问和异常日志。
     */
    private static final Logger log = LoggerFactory.getLogger(UserPodController.class);

    /**
     * 用户 Pod 生命周期编排服务。
     */
    private final PodManager podManager;

    /**
     * Kubernetes 资源读写服务。
     */
    private final KubernetesService kubernetesService;

    /**
     * qwenpaw.* 配置项。
     */
    private final QwenPawProperties properties;

    /**
     * 注入 Pod 编排、Kubernetes 操作和配置对象。
     */
    public UserPodController(PodManager podManager, KubernetesService kubernetesService, QwenPawProperties properties) {
        this.podManager = podManager;
        this.kubernetesService = kubernetesService;
        this.properties = properties;
    }

    /**
     * 创建用户 Pod；如果已经存在则直接返回现有 Pod。
     */
    @PostMapping("/users/{userId}/pod")
    public UserPodResponse createOrGetUserPod(@PathVariable String userId) {
        // 所有 Kubernetes 标签和资源名都使用统一的小写用户 ID。
        String normalizedUserId = normalizeUserId(userId);
        log.info("Processing pod request for user {}", normalizedUserId);
        UserPodMapping mapping = podManager.getOrCreateUserPod(normalizedUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to create or get user pod"));
        UserPodResponse response = UserPodResponse.from(mapping);
        response.setMessage("Pod is ready");
        return response;
    }

    /**
     * 查询指定用户当前 Pod 的映射和运行状态。
     */
    @GetMapping("/users/{userId}/pod")
    public UserPodResponse getUserPod(@PathVariable String userId) {
        String normalizedUserId = normalizeUserId(userId);
        return podManager.getUserPod(normalizedUserId)
                .map(UserPodResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User pod not found"));
    }

    /**
     * 删除指定用户的 Deployment、Service 和 HTTPRoute。
     */
    @DeleteMapping("/users/{userId}/pod")
    public Map<String, String> deleteUserPod(@PathVariable String userId) {
        String normalizedUserId = normalizeUserId(userId);
        if (!podManager.deleteUserPod(normalizedUserId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User pod not found");
        }
        return Map.of("message", "Pod deleted successfully", "user_id", normalizedUserId);
    }

    /**
     * 重启指定用户 Pod；底层会删除当前 Pod，由 Deployment 重新拉起。
     */
    @PostMapping("/users/{userId}/pod/restart")
    public UserPodResponse restartUserPod(@PathVariable String userId) {
        String normalizedUserId = normalizeUserId(userId);
        UserPodMapping mapping = podManager.restartUserPod(normalizedUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User pod not found or restart failed"));
        UserPodResponse response = UserPodResponse.from(mapping);
        response.setMessage("Pod restarted successfully");
        return response;
    }

    /**
     * 重启指定用户 Pod 内的 QwenPaw 服务进程，不删除 Pod。
     */
    @PostMapping("/users/{userId}/service/restart")
    public UserPodResponse restartUserService(@PathVariable String userId) {
        String normalizedUserId = normalizeUserId(userId);
        UserPodMapping mapping = podManager.restartUserService(normalizedUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User pod not found or service restart failed"));
        UserPodResponse response = UserPodResponse.from(mapping);
        response.setMessage("QwenPaw service restart triggered successfully");
        return response;
    }

    /**
     * 列出控制器当前能发现的所有用户 Pod。
     */
    @GetMapping("/users/pods")
    public ListUserPodsResponse listUserPods() {
        List<UserPodResponse> users = podManager.listUserPods()
                .stream()
                .map(UserPodResponse::from)
                .toList();
        return new ListUserPodsResponse(users);
    }

    /**
     * 获取指定用户 Pod 的容器日志。
     */
    @GetMapping("/users/{userId}/logs")
    public Map<String, String> getUserPodLogs(@PathVariable String userId,
                                              @RequestParam(defaultValue = "100") int tail) {
        String normalizedUserId = normalizeUserId(userId);
        // tail 控制最多返回的日志行数，避免前端一次拉取过多日志。
        String logs = podManager.getPodLogs(normalizedUserId, tail);
        if (logs == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User pod not found");
        }
        return Map.of("user_id", normalizedUserId, "logs", logs);
    }

    /**
     * 手动扫描 Kubernetes 中已有用户 Pod，并输出同步日志。
     */
    @PostMapping("/admin/sync")
    public Map<String, Object> syncMappings() {
        podManager.syncMappings();
        return Map.of("message", "Mappings synced successfully", "time", OffsetDateTime.now());
    }

    /**
     * 清理没有对应活跃用户 Pod 的 Service 和 HTTPRoute。
     */
    @PostMapping("/admin/cleanup")
    public Map<String, Object> cleanupOrphaned() {
        podManager.cleanupOrphanedResources();
        return Map.of("message", "Orphaned resources cleaned up successfully", "time", OffsetDateTime.now());
    }

    /**
     * 读取控制器自身配置 ConfigMap，供管理页面展示。
     */
    @GetMapping("/admin/config")
    public ConfigMapData getControllerConfig() {
        Map<String, String> data = kubernetesService.getConfigMap(properties.getControllerConfigmapName());
        if (data == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "ConfigMap " + properties.getControllerConfigmapName() + " not found");
        }
        return new ConfigMapData(properties.getControllerConfigmapName(), properties.getK8sNamespace(), data);
    }

    /**
     * 更新控制器自身配置 ConfigMap，并按请求决定是否滚动重启控制器。
     */
    @PutMapping("/admin/config")
    public UpdateConfigResponse updateControllerConfig(@RequestBody UpdateConfigRequest request) {
        if (request.getData() == null || request.getData().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Config data cannot be empty");
        }

        // LinkedHashMap 保留前端提交的键顺序，便于返回 updated_keys。
        Map<String, String> data = new LinkedHashMap<>(request.getData());
        if (!kubernetesService.updateConfigMap(properties.getControllerConfigmapName(), data)) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to update ConfigMap");
        }

        boolean restarted = false;
        if (request.isRestartPods()) {
            restarted = kubernetesService.restartControllerDeployment(properties.getControllerDeploymentName());
            if (!restarted) {
                log.warn("Could not restart deployment {}", properties.getControllerDeploymentName());
            }
        }

        return new UpdateConfigResponse(
                true,
                "ConfigMap updated successfully",
                List.copyOf(data.keySet()),
                restarted);
    }

    /**
     * 读取注入到用户 QwenPaw Pod 的运行时环境变量 ConfigMap。
     */
    @GetMapping("/admin/runtime-config")
    public ConfigMapData getRuntimeConfig() {
        Map<String, String> data = kubernetesService.getConfigMap(properties.getQwenpawRuntimeConfigmapName());
        if (data == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "ConfigMap " + properties.getQwenpawRuntimeConfigmapName() + " not found");
        }
        return new ConfigMapData(properties.getQwenpawRuntimeConfigmapName(), properties.getK8sNamespace(), data);
    }

    /**
     * 更新用户 QwenPaw 运行时环境变量 ConfigMap，并滚动重启所有用户 Deployment。
     */
    @PutMapping("/admin/runtime-config")
    public Map<String, Object> updateRuntimeConfig(@RequestBody UpdateConfigRequest request) {
        if (request.getData() == null || request.getData().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Config data cannot be empty");
        }

        Map<String, String> data = new LinkedHashMap<>(request.getData());
        // 返回值小于 0 表示 ConfigMap 本身更新失败。
        int restarted = kubernetesService.refreshQwenPawRuntimeConfig(data);
        if (restarted < 0) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to update runtime ConfigMap");
        }

        return Map.of(
                "success", true,
                "message", "Runtime ConfigMap updated successfully",
                "updated_keys", List.copyOf(data.keySet()),
                "restarted_deployments", restarted);
    }

    /**
     * 校验并规范化用户 ID，保证后续资源查找使用同一套标签值。
     */
    private String normalizeUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "user_id is required");
        }
        return userId.toLowerCase(Locale.ROOT);
    }
}
