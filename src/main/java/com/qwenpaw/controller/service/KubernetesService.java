package com.qwenpaw.controller.service;

import com.qwenpaw.controller.config.QwenPawProperties;
import com.qwenpaw.controller.model.PodStatus;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ContainerPortBuilder;
import io.fabric8.kubernetes.api.model.EnvFromSource;
import io.fabric8.kubernetes.api.model.EnvFromSourceBuilder;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimVolumeSourceBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.ServicePortBuilder;
import io.fabric8.kubernetes.api.model.TCPSocketActionBuilder;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@org.springframework.stereotype.Service
public class KubernetesService {

    private static final Logger log = LoggerFactory.getLogger(KubernetesService.class);
    private static final ResourceDefinitionContext HTTP_ROUTE_CONTEXT = new ResourceDefinitionContext.Builder()
            .withGroup("gateway.networking.k8s.io")
            .withVersion("v1")
            .withPlural("httproutes")
            .withNamespaced(true)
            .build();

    private final KubernetesClient client;
    private final QwenPawProperties properties;

    public KubernetesService(KubernetesClient client, QwenPawProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    public PodStatus getPodStatus(String podName) {
        Pod pod = getPodByName(podName);
        if (pod == null || pod.getStatus() == null) {
            return PodStatus.UNKNOWN;
        }
        return mapPhaseToStatus(pod.getStatus().getPhase());
    }

    public Pod getPodByName(String podName) {
        return client.pods()
                .inNamespace(properties.getK8sNamespace())
                .withName(podName)
                .get();
    }

    public List<Pod> listPodsByLabel(Map<String, String> labels) {
        return client.pods()
                .inNamespace(properties.getK8sNamespace())
                .withLabels(labels)
                .list()
                .getItems();
    }

    public String createDeployment(String userId) {
        String deploymentName = resourceName(userId);
        Deployment existing = client.apps()
                .deployments()
                .inNamespace(properties.getK8sNamespace())
                .withName(deploymentName)
                .get();
        if (existing != null) {
            log.info("Deployment {} already exists", deploymentName);
            return deploymentName;
        }

        Map<String, String> labels = managedLabels(userId);
        Deployment deployment = new DeploymentBuilder()
                .withApiVersion("apps/v1")
                .withKind("Deployment")
                .withNewMetadata()
                .withName(deploymentName)
                .withNamespace(properties.getK8sNamespace())
                .withLabels(labels)
                .endMetadata()
                .withNewSpec()
                .withReplicas(1)
                .withNewSelector()
                .withMatchLabels(Map.of("app", properties.getQwenpawAppLabel(), "user", userId))
                .endSelector()
                .withNewTemplate()
                .withNewMetadata()
                .withLabels(Map.of("app", properties.getQwenpawAppLabel(), "user", userId))
                .endMetadata()
                .withNewSpec()
                .addToInitContainers(new ContainerBuilder()
                        .withName("init-config")
                        .withImage("busybox:1.35")
                        .withCommand("sh", "-c", initConfigCommand())
                        .withVolumeMounts(
                                volumeMount("template-volume", "/app/qwenpaw-public", properties.getQwenpawPublicTemplateSubPath(), true),
                                volumeMount("data-volume", "/app/working", userId + "/working"),
                                volumeMount("secrets-volume", "/app/working.secret", userId + "/working.secret"),
                                volumeMount("backups-volume", "/app/working.backups", userId + "/working.backups"))
                        .build())
                .addToContainers(new ContainerBuilder()
                        .withName("qwenpaw")
                        .withImage(properties.getQwenpawImage())
                        .withImagePullPolicy("IfNotPresent")
                        .withPorts(new ContainerPortBuilder()
                                .withName("http")
                                .withContainerPort(properties.getQwenpawContainerPort())
                                .build())
                        .withResources(new ResourceRequirementsBuilder()
                                .withRequests(resourceMap(properties.getResourceRequestsCpu(), properties.getResourceRequestsMemory()))
                                .withLimits(resourceMap(properties.getResourceLimitsCpu(), properties.getResourceLimitsMemory()))
                                .build())
                        .withVolumeMounts(
                                volumeMount("config-volume", "/app/config.yaml", "config.yaml"),
                                volumeMount("data-volume", "/app/working", userId + "/working"),
                                volumeMount("secrets-volume", "/app/working.secret", userId + "/working.secret"),
                                volumeMount("backups-volume", "/app/working.backups", userId + "/working.backups"))
                        .withEnv(
                                new EnvVarBuilder().withName("QWENPAW_WORKING_DIR").withValue("/app/working").build(),
                                new EnvVarBuilder().withName("QWENPAW_SECRET_DIR").withValue("/app/working.secret").build(),
                                new EnvVarBuilder().withName("QWENPAW_CONFIG_FILE").withValue("config.json").build(),
                                new EnvVarBuilder().withName("USER_ID").withValue(userId).build(),
                                new EnvVarBuilder().withName("QWENPAW_USER").withValue(userId).build(),
                                new EnvVarBuilder().withName("QWENPAW_AUTH_ENABLED").withValue("true").build(),
                                new EnvVarBuilder().withName("QWENPAW_AUTH_USERNAME").withValue("admin").build(),
                                new EnvVarBuilder().withName("QWENPAW_AUTH_PASSWORD").withValue("admin123").build())
                        .withEnvFrom(runtimeConfigEnvFrom())
                        .withNewLivenessProbe()
                        .withTcpSocket(new TCPSocketActionBuilder()
                                .withNewPort(properties.getQwenpawContainerPort())
                                .build())
                        .withInitialDelaySeconds(properties.getLivenessProbeInitialDelay())
                        .withPeriodSeconds(properties.getLivenessProbePeriod())
                        .withTimeoutSeconds(properties.getLivenessProbeTimeout())
                        .withFailureThreshold(properties.getLivenessProbeFailureThreshold())
                        .endLivenessProbe()
                        .withNewReadinessProbe()
                        .withTcpSocket(new TCPSocketActionBuilder()
                                .withNewPort(properties.getQwenpawContainerPort())
                                .build())
                        .withInitialDelaySeconds(properties.getReadinessProbeInitialDelay())
                        .withPeriodSeconds(properties.getReadinessProbePeriod())
                        .withTimeoutSeconds(properties.getReadinessProbeTimeout())
                        .withFailureThreshold(properties.getReadinessProbeFailureThreshold())
                        .endReadinessProbe()
                        .build())
                .withVolumes(
                        new VolumeBuilder()
                                .withName("config-volume")
                                .withNewConfigMap()
                                .withName(properties.getQwenpawConfigmapName())
                                .endConfigMap()
                                .build(),
                        pvcVolume("template-volume"),
                        pvcVolume("data-volume"),
                        pvcVolume("secrets-volume"),
                        pvcVolume("backups-volume"))
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();

        client.apps().deployments().inNamespace(properties.getK8sNamespace()).resource(deployment).create();
        log.info("Created deployment {}", deploymentName);
        return deploymentName;
    }

    public boolean deleteDeployment(String deploymentName) {
        return client.apps()
                .deployments()
                .inNamespace(properties.getK8sNamespace())
                .withName(deploymentName)
                .delete()
                .size() > 0;
    }

    public String createService(String userId) {
        String serviceName = resourceName(userId);
        Service existing = getService(serviceName);
        if (existing != null) {
            log.info("Service {} already exists", serviceName);
            return serviceName;
        }

        Service service = new ServiceBuilder()
                .withApiVersion("v1")
                .withKind("Service")
                .withNewMetadata()
                .withName(serviceName)
                .withNamespace(properties.getK8sNamespace())
                .withLabels(managedLabels(userId))
                .endMetadata()
                .withNewSpec()
                .withSelector(Map.of("app", properties.getQwenpawAppLabel(), "user", userId))
                .withPorts(new ServicePortBuilder()
                        .withName("http")
                        .withPort(properties.getQwenpawContainerPort())
                        .withNewTargetPort(properties.getQwenpawContainerPort())
                        .build())
                .withType("ClusterIP")
                .endSpec()
                .build();

        client.services().inNamespace(properties.getK8sNamespace()).resource(service).create();
        log.info("Created service {}", serviceName);
        return serviceName;
    }

    public Service getService(String serviceName) {
        return client.services()
                .inNamespace(properties.getK8sNamespace())
                .withName(serviceName)
                .get();
    }

    public boolean deleteService(String serviceName) {
        return client.services()
                .inNamespace(properties.getK8sNamespace())
                .withName(serviceName)
                .delete()
                .size() > 0;
    }

    public String createHttpRoute(String userId) {
        String routeName = resourceName(userId);
        if (getHttpRoute(routeName).isPresent()) {
            log.info("HTTPRoute {} already exists", routeName);
            return routeName;
        }

        Map<String, Object> spec = Map.of(
                "parentRefs", List.of(Map.of(
                        "name", properties.getGatewayName(),
                        "namespace", properties.getGatewayNamespace())),
                "rules", List.of(Map.of(
                        "matches", List.of(Map.of("headers", List.of(Map.of(
                                "name", "Cookie",
                                "type", "RegularExpression",
                                "value", "(?:^|;\\s*)userid=" + escapeRegex(userId) + "(?:$|;)")))),
                        "backendRefs", List.of(Map.of(
                                "name", routeName,
                                "port", properties.getQwenpawContainerPort(),
                                "weight", 1)))));

        GenericKubernetesResource httpRoute = new GenericKubernetesResource();
        httpRoute.setApiVersion("gateway.networking.k8s.io/v1");
        httpRoute.setKind("HTTPRoute");
        httpRoute.setMetadata(new ObjectMetaBuilder()
                .withName(routeName)
                .withNamespace(properties.getK8sNamespace())
                .withLabels(managedLabels(userId))
                .build());
        httpRoute.setAdditionalProperty("spec", spec);

        client.genericKubernetesResources(HTTP_ROUTE_CONTEXT)
                .inNamespace(properties.getK8sNamespace())
                .resource(httpRoute)
                .create();
        log.info("Created HTTPRoute {}", routeName);
        return routeName;
    }

    public boolean deleteHttpRoute(String routeName) {
        return client.genericKubernetesResources(HTTP_ROUTE_CONTEXT)
                .inNamespace(properties.getK8sNamespace())
                .withName(routeName)
                .delete()
                .size() > 0;
    }

    public List<Service> listManagedServices() {
        return client.services()
                .inNamespace(properties.getK8sNamespace())
                .withLabel("managed-by", "qwenpaw-controller")
                .list()
                .getItems();
    }

    public List<GenericKubernetesResource> listManagedHttpRoutes() {
        return client.genericKubernetesResources(HTTP_ROUTE_CONTEXT)
                .inNamespace(properties.getK8sNamespace())
                .withLabel("managed-by", "qwenpaw-controller")
                .list()
                .getItems()
                .stream()
                .toList();
    }

    public boolean waitForPodReady(String deploymentName) {
        String userId = deploymentName.replaceFirst("^qwenpaw-", "");
        Instant deadline = Instant.now().plusSeconds(properties.getPodReadyTimeout());
        while (Instant.now().isBefore(deadline)) {
            List<Pod> pods = listPodsByLabel(Map.of("user", userId));
            if (!pods.isEmpty()) {
                Pod pod = pods.get(0);
                String phase = pod.getStatus() == null ? null : pod.getStatus().getPhase();
                if ("Failed".equals(phase)) {
                    log.error("Pod {} failed", pod.getMetadata().getName());
                    return false;
                }
                if ("Running".equals(phase) && isReady(pod)) {
                    log.info("Pod {} is ready", pod.getMetadata().getName());
                    return true;
                }
            }
            sleep();
        }
        log.error("Timeout waiting for deployment {} pod to be ready", deploymentName);
        return false;
    }

    public String getPodLogs(String podName, int tailLines) {
        try {
            return client.pods()
                    .inNamespace(properties.getK8sNamespace())
                    .withName(podName)
                    .tailingLines(tailLines)
                    .getLog();
        } catch (KubernetesClientException e) {
            log.error("Failed to get pod logs for {}", podName, e);
            return "";
        }
    }

    public boolean restartPod(String podName) {
        return client.pods()
                .inNamespace(properties.getK8sNamespace())
                .withName(podName)
                .delete()
                .size() > 0;
    }

    public Map<String, String> getConfigMap(String configMapName) {
        ConfigMap configMap = client.configMaps()
                .inNamespace(properties.getK8sNamespace())
                .withName(configMapName)
                .get();
        return configMap == null ? null : Optional.ofNullable(configMap.getData()).orElseGet(Map::of);
    }

    public boolean updateConfigMap(String configMapName, Map<String, String> data) {
        ConfigMap configMap = client.configMaps()
                .inNamespace(properties.getK8sNamespace())
                .withName(configMapName)
                .get();
        if (configMap == null) {
            return false;
        }
        configMap.setData(new LinkedHashMap<>(data));
        client.configMaps()
                .inNamespace(properties.getK8sNamespace())
                .resource(configMap)
                .update();
        log.info("Updated ConfigMap {}", configMapName);
        return true;
    }

    public boolean restartControllerDeployment(String deploymentName) {
        Deployment deployment = client.apps()
                .deployments()
                .inNamespace(properties.getK8sNamespace())
                .withName(deploymentName)
                .get();
        if (deployment == null) {
            return false;
        }
        client.apps()
                .deployments()
                .inNamespace(properties.getK8sNamespace())
                .withName(deploymentName)
                .edit(d -> new DeploymentBuilder(d)
                        .editSpec()
                        .editTemplate()
                        .editMetadata()
                        .addToAnnotations("kubectl.kubernetes.io/restartedAt", OffsetDateTime.now(ZoneOffset.UTC).toString())
                        .endMetadata()
                        .endTemplate()
                        .endSpec()
                        .build());
        log.info("Triggered rolling restart for deployment {}", deploymentName);
        return true;
    }

    public int refreshQwenPawRuntimeConfig(Map<String, String> data) {
        if (!updateConfigMap(properties.getQwenpawRuntimeConfigmapName(), data)) {
            return -1;
        }

        int restarted = 0;
        String restartedAt = OffsetDateTime.now(ZoneOffset.UTC).toString();
        List<Deployment> deployments = client.apps()
                .deployments()
                .inNamespace(properties.getK8sNamespace())
                .withLabel("app", properties.getQwenpawAppLabel())
                .list()
                .getItems();

        for (Deployment deployment : deployments) {
            Deployment updated = ensureRuntimeConfigEnvFrom(deployment, restartedAt);
            client.apps()
                    .deployments()
                    .inNamespace(properties.getK8sNamespace())
                    .resource(updated)
                    .update();
            restarted++;
        }

        log.info("Updated runtime ConfigMap {} and restarted {} QwenPaw deployments",
                properties.getQwenpawRuntimeConfigmapName(), restarted);
        return restarted;
    }

    private Optional<GenericKubernetesResource> getHttpRoute(String routeName) {
        GenericKubernetesResource route = client.genericKubernetesResources(HTTP_ROUTE_CONTEXT)
                .inNamespace(properties.getK8sNamespace())
                .withName(routeName)
                .get();
        return Optional.ofNullable(route);
    }

    private PodStatus mapPhaseToStatus(String phase) {
        return switch (phase == null ? "" : phase) {
            case "Running" -> PodStatus.RUNNING;
            case "Pending" -> PodStatus.PENDING;
            case "Failed" -> PodStatus.FAILED;
            default -> PodStatus.UNKNOWN;
        };
    }

    private boolean isReady(Pod pod) {
        if (pod.getStatus() == null || pod.getStatus().getConditions() == null) {
            return false;
        }
        return pod.getStatus().getConditions().stream()
                .anyMatch(condition -> "Ready".equals(condition.getType()) && "True".equals(condition.getStatus()));
    }

    private String resourceName(String userId) {
        return "qwenpaw-" + userId;
    }

    private String escapeRegex(String value) {
        return value.replaceAll("([\\\\.\\[\\]{}()*+?^$|])", "\\\\$1");
    }

    private Map<String, String> managedLabels(String userId) {
        Map<String, String> labels = new HashMap<>();
        labels.put("app", properties.getQwenpawAppLabel());
        labels.put("user", userId);
        labels.put("managed-by", "qwenpaw-controller");
        return labels;
    }

    private Map<String, Quantity> resourceMap(String cpu, String memory) {
        return Map.of("cpu", new Quantity(cpu), "memory", new Quantity(memory));
    }

    private Deployment ensureRuntimeConfigEnvFrom(Deployment deployment, String restartedAt) {
        DeploymentBuilder builder = new DeploymentBuilder(deployment)
                .editSpec()
                .editTemplate()
                .editOrNewMetadata()
                .addToAnnotations("kubectl.kubernetes.io/restartedAt", restartedAt)
                .endMetadata()
                .endTemplate()
                .endSpec();

        Deployment updated = builder.build();
        List<Container> containers = updated.getSpec().getTemplate().getSpec().getContainers();
        for (Container container : containers) {
            if ("qwenpaw".equals(container.getName()) && !hasRuntimeConfigEnvFrom(container)) {
                List<EnvFromSource> envFrom = container.getEnvFrom();
                if (envFrom == null) {
                    container.setEnvFrom(List.of(runtimeConfigEnvFrom()));
                } else {
                    envFrom = new ArrayList<>(envFrom);
                    envFrom.add(runtimeConfigEnvFrom());
                    container.setEnvFrom(envFrom);
                }
            }
        }
        return updated;
    }

    private boolean hasRuntimeConfigEnvFrom(Container container) {
        List<EnvFromSource> envFrom = container.getEnvFrom();
        if (envFrom == null) {
            return false;
        }
        return envFrom.stream()
                .anyMatch(source -> source.getConfigMapRef() != null
                        && properties.getQwenpawRuntimeConfigmapName().equals(source.getConfigMapRef().getName()));
    }

    private EnvFromSource runtimeConfigEnvFrom() {
        return new EnvFromSourceBuilder()
                .withNewConfigMapRef(properties.getQwenpawRuntimeConfigmapName(), false)
                .build();
    }

    private io.fabric8.kubernetes.api.model.VolumeMount volumeMount(String name, String mountPath, String subPath, boolean readOnly) {
        return new VolumeMountBuilder()
                .withName(name)
                .withMountPath(mountPath)
                .withSubPath(subPath)
                .withReadOnly(readOnly)
                .build();
    }

    private io.fabric8.kubernetes.api.model.VolumeMount volumeMount(String name, String mountPath, String subPath) {
        return new VolumeMountBuilder()
                .withName(name)
                .withMountPath(mountPath)
                .withSubPath(subPath)
                .build();
    }

    private io.fabric8.kubernetes.api.model.Volume pvcVolume(String name) {
        return new VolumeBuilder()
                .withName(name)
                .withPersistentVolumeClaim(new PersistentVolumeClaimVolumeSourceBuilder()
                        .withClaimName(properties.getQwenpawNasPvcName())
                        .build())
                .build();
    }

    private String initConfigCommand() {
        return "mkdir -p /app/working /app/working.secret /app/working.backups && "
                + "cp -af /app/qwenpaw-public/working/. /app/working/ && "
                + "cp -af /app/qwenpaw-public/working.secret/. /app/working.secret/ && "
                + "if [ -d /app/qwenpaw-public/working.backups ]; then cp -af /app/qwenpaw-public/working.backups/. /app/working.backups/; fi";
    }

    private void sleep() {
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for pod readiness", e);
        }
    }
}
