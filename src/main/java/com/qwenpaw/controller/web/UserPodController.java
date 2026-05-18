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

@RestController
@RequestMapping("/api/v1")
public class UserPodController {

    private static final Logger log = LoggerFactory.getLogger(UserPodController.class);

    private final PodManager podManager;
    private final KubernetesService kubernetesService;
    private final QwenPawProperties properties;

    public UserPodController(PodManager podManager, KubernetesService kubernetesService, QwenPawProperties properties) {
        this.podManager = podManager;
        this.kubernetesService = kubernetesService;
        this.properties = properties;
    }

    @PostMapping("/users/{userId}/pod")
    public UserPodResponse createOrGetUserPod(@PathVariable String userId) {
        String normalizedUserId = normalizeUserId(userId);
        log.info("Processing pod request for user {}", normalizedUserId);
        UserPodMapping mapping = podManager.getOrCreateUserPod(normalizedUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to create or get user pod"));
        UserPodResponse response = UserPodResponse.from(mapping);
        response.setMessage("Pod is ready");
        return response;
    }

    @GetMapping("/users/{userId}/pod")
    public UserPodResponse getUserPod(@PathVariable String userId) {
        String normalizedUserId = normalizeUserId(userId);
        return podManager.getUserPod(normalizedUserId)
                .map(UserPodResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User pod not found"));
    }

    @DeleteMapping("/users/{userId}/pod")
    public Map<String, String> deleteUserPod(@PathVariable String userId) {
        String normalizedUserId = normalizeUserId(userId);
        if (!podManager.deleteUserPod(normalizedUserId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User pod not found");
        }
        return Map.of("message", "Pod deleted successfully", "user_id", normalizedUserId);
    }

    @GetMapping("/users/pods")
    public ListUserPodsResponse listUserPods() {
        List<UserPodResponse> users = podManager.listUserPods()
                .stream()
                .map(UserPodResponse::from)
                .toList();
        return new ListUserPodsResponse(users);
    }

    @GetMapping("/users/{userId}/logs")
    public Map<String, String> getUserPodLogs(@PathVariable String userId,
                                              @RequestParam(defaultValue = "100") int tail) {
        String normalizedUserId = normalizeUserId(userId);
        String logs = podManager.getPodLogs(normalizedUserId, tail);
        if (logs == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User pod not found");
        }
        return Map.of("user_id", normalizedUserId, "logs", logs);
    }

    @PostMapping("/admin/sync")
    public Map<String, Object> syncMappings() {
        podManager.syncMappings();
        return Map.of("message", "Mappings synced successfully", "time", OffsetDateTime.now());
    }

    @PostMapping("/admin/cleanup")
    public Map<String, Object> cleanupOrphaned() {
        podManager.cleanupOrphanedResources();
        return Map.of("message", "Orphaned resources cleaned up successfully", "time", OffsetDateTime.now());
    }

    @GetMapping("/admin/config")
    public ConfigMapData getControllerConfig() {
        Map<String, String> data = kubernetesService.getConfigMap(properties.getControllerConfigmapName());
        if (data == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "ConfigMap " + properties.getControllerConfigmapName() + " not found");
        }
        return new ConfigMapData(properties.getControllerConfigmapName(), properties.getK8sNamespace(), data);
    }

    @PutMapping("/admin/config")
    public UpdateConfigResponse updateControllerConfig(@RequestBody UpdateConfigRequest request) {
        if (request.getData() == null || request.getData().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Config data cannot be empty");
        }

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

    private String normalizeUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "user_id is required");
        }
        return userId.toLowerCase(Locale.ROOT);
    }
}
