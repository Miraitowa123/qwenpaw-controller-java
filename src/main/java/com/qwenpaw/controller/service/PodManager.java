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

@Component
public class PodManager {

    private static final Logger log = LoggerFactory.getLogger(PodManager.class);

    private final KubernetesService kubernetesService;
    private final QwenPawProperties properties;

    public PodManager(KubernetesService kubernetesService, QwenPawProperties properties) {
        this.kubernetesService = kubernetesService;
        this.properties = properties;
    }

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

    public Optional<UserPodMapping> getOrCreateUserPod(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }

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

    public Optional<UserPodMapping> getUserPod(String userId) {
        return findUserPod(userId)
                .map(mapping -> {
                    mapping.setStatus(kubernetesService.getPodStatus(mapping.getPodName()));
                    return mapping;
                });
    }

    public List<UserPodMapping> listUserPods() {
        return kubernetesService.listPodsByLabel(Map.of("app", properties.getQwenpawAppLabel()))
                .stream()
                .filter(pod -> pod.getMetadata() != null
                        && pod.getMetadata().getLabels() != null
                        && pod.getMetadata().getLabels().containsKey("user"))
                .map(this::mappingFromPod)
                .toList();
    }

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

    public void syncMappings() {
        List<UserPodMapping> mappings = listUserPods();
        mappings.forEach(mapping -> log.info("Synced user {} pod {} status {}",
                mapping.getUserId(), mapping.getPodName(), mapping.getStatus()));
    }

    public void cleanupOrphanedResources() {
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

    public String getPodLogs(String userId, int tailLines) {
        return getUserPod(userId)
                .map(mapping -> kubernetesService.getPodLogs(mapping.getPodName(), tailLines))
                .orElse(null);
    }

    private Optional<UserPodMapping> createUserPod(String userId) {
        UserPodMapping mapping = newMapping(userId);
        mapping.setStatus(PodStatus.CREATING);

        try {
            kubernetesService.createDeployment(userId);
            if (!kubernetesService.waitForPodReady(mapping.getDeploymentName())) {
                mapping.setStatus(PodStatus.FAILED);
                return Optional.empty();
            }

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

    private Optional<UserPodMapping> findUserPod(String userId) {
        List<Pod> pods = kubernetesService.listPodsByLabel(Map.of(
                "app", properties.getQwenpawAppLabel(),
                "user", userId));
        if (pods.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(mappingFromPod(pods.get(0)));
    }

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

    private UserPodMapping newMapping(String userId) {
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

    private String normalizeBasePath(String basePathPrefix) {
        if (basePathPrefix == null || basePathPrefix.isBlank() || "/".equals(basePathPrefix)) {
            return "";
        }
        String normalized = basePathPrefix.startsWith("/") ? basePathPrefix : "/" + basePathPrefix;
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
