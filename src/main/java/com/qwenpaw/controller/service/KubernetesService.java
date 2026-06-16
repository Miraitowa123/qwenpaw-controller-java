package com.qwenpaw.controller.service;

import com.qwenpaw.controller.config.QwenPawProperties;
import com.qwenpaw.controller.model.PodStatus;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ContainerPortBuilder;
import io.fabric8.kubernetes.api.model.EnvFromSource;
import io.fabric8.kubernetes.api.model.EnvFromSourceBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
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
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 封装所有直接访问 Kubernetes API 的操作。
 */
@org.springframework.stereotype.Service
public class KubernetesService {

    /**
     * Kubernetes 资源操作日志。
     */
    private static final Logger log = LoggerFactory.getLogger(KubernetesService.class);

    /**
     * Gateway API HTTPRoute 自定义资源的 Fabric8 定义。
     */
    private static final ResourceDefinitionContext HTTP_ROUTE_CONTEXT = new ResourceDefinitionContext.Builder()
            .withGroup("gateway.networking.k8s.io")
            .withVersion("v1")
            .withPlural("httproutes")
            .withNamespaced(true)
            .build();

    /**
     * Fabric8 Kubernetes 客户端。
     */
    private final KubernetesClient client;

    /**
     * qwenpaw.* 配置项。
     */
    private final QwenPawProperties properties;

    /**
     * 负责为用户创建个人 API Key 的服务。
     */
    private final PersonalApiKeyService personalApiKeyService;

    /**
     * 根据 RUN_ENV 解析 ELLM 端点的服务。
     */
    private final EllmEndpointResolver ellmEndpointResolver;

    /**
     * 注入 Kubernetes 客户端、配置对象、个人 API Key 服务和 ELLM 端点解析服务。
     */
    public KubernetesService(KubernetesClient client,
                             QwenPawProperties properties,
                             PersonalApiKeyService personalApiKeyService,
                             EllmEndpointResolver ellmEndpointResolver) {
        this.client = client;
        this.properties = properties;
        this.personalApiKeyService = personalApiKeyService;
        this.ellmEndpointResolver = ellmEndpointResolver;
    }

    /**
     * 查询指定 Pod 的业务状态。
     */
    public PodStatus getPodStatus(String podName) {
        Pod pod = getPodByName(podName);
        if (pod == null || pod.getStatus() == null) {
            return PodStatus.UNKNOWN;
        }
        return mapPhaseToStatus(pod.getStatus().getPhase());
    }

    /**
     * 按名称读取 Pod。
     */
    public Pod getPodByName(String podName) {
        return client.pods()
                .inNamespace(properties.getK8sNamespace())
                .withName(podName)
                .get();
    }

    /**
     * 按标签查询 Pod 列表。
     */
    public List<Pod> listPodsByLabel(Map<String, String> labels) {
        return client.pods()
                .inNamespace(properties.getK8sNamespace())
                .withLabels(labels)
                .list()
                .getItems();
    }

    /**
     * 为用户创建 Deployment；Deployment 的 Pod 模板决定 initContainer、主容器和卷挂载。
     */
    public String createDeployment(String userId) {
        // 用户相关 Kubernetes 资源统一使用 qwenpaw-{userId} 命名。
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
        // initVolumeMounts 只给 initContainer 用，先准备模板和用户目录。
        List<VolumeMount> initVolumeMounts = initVolumeMounts(userId);
        // qwenpawVolumeMounts 给主业务容器用，决定 /app/working 等路径来自哪里。
        List<VolumeMount> qwenpawVolumeMounts = qwenpawVolumeMounts(userId);
        // volumes 是 Pod 级别声明，容器通过 volumeMounts 引用这些卷。
        List<Volume> volumes = podVolumes();
        // ellmEndpoint 来自 qwenpaw-runtime-config 的 RUN_ENV，用于统一生成 OMSERVICE 和 ADAPTER 地址。
        EllmEndpointResolver.EllmEndpoint ellmEndpoint = ellmEndpointResolver.resolve();
        // 只有真正新建 Deployment 时才创建个人 API Key，重启已有 Pod 不会再次调用外部接口。
        String personalApiKey = personalApiKeyService.createPersonalApiKey(userId, ellmEndpoint);
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
                        // initContainer 先执行初始化命令，执行完成后主 qwenpaw 容器才会启动。
                        .withCommand("sh", "-c", initConfigCommand(userId))
                        .withEnv(
                                new EnvVarBuilder()
                                        .withName("PERSONAL_API_KEY")
                                        .withValue(personalApiKey)
                                        .build(),
                                new EnvVarBuilder()
                                        .withName("ELLM_ADAPTER_BASE_URL")
                                        .withValue(ellmEndpoint.adapterBaseUrl())
                                        .build())
                        .withVolumeMounts(initVolumeMounts)
                        .build())
                .addToContainers(new ContainerBuilder()
                        .withName("qwenpaw")
                        .withImage(properties.getQwenpawImage())
                        .withImagePullPolicy("IfNotPresent")
                        .withPorts(new ContainerPortBuilder()
                                .withName("http")
                                .withContainerPort(properties.getQwenpawContainerPort())
                                .build())
                        // requests 影响调度预留，limits 是容器实际可用资源上限。
                        .withResources(new ResourceRequirementsBuilder()
                                .withRequests(resourceMap(properties.getResourceRequestsCpu(), properties.getResourceRequestsMemory()))
                                .withLimits(resourceMap(properties.getResourceLimitsCpu(), properties.getResourceLimitsMemory()))
                                .build())
                        .withVolumeMounts(qwenpawVolumeMounts)
                        .withEnv(qwenpawExplicitEnv(userId))
                        // envFrom 会把运行时 ConfigMap 的所有 key 注入为环境变量。
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
                .withVolumes(volumes)
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();

        client.apps().deployments().inNamespace(properties.getK8sNamespace()).resource(deployment).create();
        log.info("Created deployment {}", deploymentName);
        return deploymentName;
    }

    /**
     * 删除指定 Deployment。
     */
    public boolean deleteDeployment(String deploymentName) {
        return client.apps()
                .deployments()
                .inNamespace(properties.getK8sNamespace())
                .withName(deploymentName)
                .delete()
                .size() > 0;
    }

    /**
     * 为用户创建 ClusterIP Service，用于 HTTPRoute 转发到用户 Pod。
     */
    public String createService(String userId) {
        String serviceName = resourceName(userId);
        Service existing = getService(serviceName);
        if (existing != null) {
            log.info("Service {} already exists", serviceName);
            return serviceName;
        }

        // Service 通过 app + user 标签选择对应用户的 Pod。
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

    /**
     * 按名称读取 Service。
     */
    public Service getService(String serviceName) {
        return client.services()
                .inNamespace(properties.getK8sNamespace())
                .withName(serviceName)
                .get();
    }

    /**
     * 删除指定 Service。
     */
    public boolean deleteService(String serviceName) {
        return client.services()
                .inNamespace(properties.getK8sNamespace())
                .withName(serviceName)
                .delete()
                .size() > 0;
    }

    /**
     * 创建 HTTPRoute，把带有 userid Cookie 的请求转发到对应用户 Service。
     */
    public String createHttpRoute(String userId) {
        String routeName = resourceName(userId);
        if (getHttpRoute(routeName).isPresent()) {
            log.info("HTTPRoute {} already exists", routeName);
            return routeName;
        }

        // Gateway API 资源这里用通用 Map 构造，便于 Fabric8 操作 CRD。
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

        // GenericKubernetesResource 表示 Fabric8 没有强类型模型的 HTTPRoute CRD。
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

    /**
     * 删除指定 HTTPRoute。
     */
    public boolean deleteHttpRoute(String routeName) {
        return client.genericKubernetesResources(HTTP_ROUTE_CONTEXT)
                .inNamespace(properties.getK8sNamespace())
                .withName(routeName)
                .delete()
                .size() > 0;
    }

    /**
     * 列出控制器管理的 Service。
     */
    public List<Service> listManagedServices() {
        return client.services()
                .inNamespace(properties.getK8sNamespace())
                .withLabel("managed-by", "qwenpaw-controller")
                .list()
                .getItems();
    }

    /**
     * 列出控制器管理的 HTTPRoute。
     */
    public List<GenericKubernetesResource> listManagedHttpRoutes() {
        return client.genericKubernetesResources(HTTP_ROUTE_CONTEXT)
                .inNamespace(properties.getK8sNamespace())
                .withLabel("managed-by", "qwenpaw-controller")
                .list()
                .getItems()
                .stream()
                .toList();
    }

    /**
     * 等待指定 Deployment 拉起的用户 Pod 进入 Running 且 Ready 状态。
     */
    public boolean waitForPodReady(String deploymentName) {
        String userId = deploymentName.replaceFirst("^qwenpaw-", "");
        // deadline 是本轮等待的截止时间，避免接口无限阻塞。
        Instant deadline = Instant.now().plusSeconds(properties.getPodReadyTimeout());
        while (Instant.now().isBefore(deadline)) {
            // 当前用户正常只有 1 个 Pod，取第一个检查状态。
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

    /**
     * 读取指定 Pod 最近的日志。
     */
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

    /**
     * 删除当前 Pod 触发重启；如果 Pod 属于 Deployment，Deployment 会自动创建新 Pod。
     */
    public boolean restartPod(String podName) {
        return client.pods()
                .inNamespace(properties.getK8sNamespace())
                .withName(podName)
                .delete()
                .size() > 0;
    }

    /**
     * 在业务容器内优雅重启 QwenPaw 应用进程。
     */
    public boolean restartQwenPawService(String podName) {
        int port = properties.getQwenpawContainerPort();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        ExecWatch execWatch = null;
        try {
            execWatch = client.pods()
                    .inNamespace(properties.getK8sNamespace())
                    .withName(podName)
                    .inContainer("qwenpaw")
                    .writingOutput(stdout)
                    .writingError(stderr)
                    .exec("sh", "-c", gracefulQwenPawServiceRestartCommand(port));

            Integer exitCode = execWatch.exitCode().get(75, TimeUnit.SECONDS);
            if (exitCode != null && exitCode == 0) {
                log.info("Gracefully restarted QwenPaw service processes in pod {}", podName);
                return true;
            }

            log.warn("Graceful QwenPaw service restart command failed in pod {} with exit code {}, stdout={}, stderr={}",
                    podName, exitCode, execOutput(stdout), execOutput(stderr));
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while gracefully restarting QwenPaw service in pod {}", podName, e);
            return false;
        } catch (Exception e) {
            log.warn("Failed to gracefully restart QwenPaw service in pod {}", podName, e);
            return false;
        } finally {
            if (execWatch != null) {
                execWatch.close();
            }
        }
    }

    /**
     * 生成容器内执行的优雅重启命令，避免直接调用 qwenpaw shutdown。
     */
    private String gracefulQwenPawServiceRestartCommand(int port) {
        return "set -u; "
                + "port=" + port + "; "
                + "supervisor_conf=/etc/supervisor/conf.d/supervisord.conf; "
                + "qwenpaw_pids() { "
                + "ps -eo pid=,args= | awk -v port=\"$port\" '$0 ~ /[q]wenpaw app/ && ($0 ~ (\"--port \" port) || $0 ~ (\"--port=\" port)) {print $1}'; "
                + "}; "
                + "if command -v supervisorctl >/dev/null 2>&1 && [ -f \"$supervisor_conf\" ] "
                + "&& supervisorctl -c \"$supervisor_conf\" status >/tmp/qwenpaw-supervisor-status 2>/tmp/qwenpaw-supervisor-error; then "
                + "programs=$(awk '$2 == \"RUNNING\" && $1 ~ /qwenpaw|app/ {print $1}' /tmp/qwenpaw-supervisor-status); "
                + "if [ -n \"$programs\" ]; then "
                + "echo \"Gracefully stopping supervisor programs: $programs\"; "
                + "for program in $programs; do echo \"Stopping $program\"; supervisorctl -c \"$supervisor_conf\" stop \"$program\" || exit 1; done; "
                + "echo \"Starting supervisor programs: $programs\"; "
                + "for program in $programs; do echo \"Starting $program\"; supervisorctl -c \"$supervisor_conf\" start \"$program\" || exit 1; done; "
                + "exit 0; "
                + "fi; "
                + "fi; "
                + "pids=$(qwenpaw_pids); "
                + "if [ -z \"$pids\" ]; then "
                + "pids=$(ps -eo pid=,args= | awk '$0 ~ /[q]wenpaw app/ {print $1}'); "
                + "fi; "
                + "if [ -z \"$pids\" ]; then echo 'No qwenpaw app process found' >&2; exit 1; fi; "
                + "echo \"Gracefully terminating qwenpaw app pids: $pids\"; "
                + "for pid in $pids; do echo \"Sending SIGTERM to $pid\"; kill -TERM \"$pid\" || exit 1; sleep 1; done; "
                + "i=0; alive=\"$pids\"; "
                + "while [ \"$i\" -lt 30 ]; do "
                + "alive=''; "
                + "for pid in $pids; do if kill -0 \"$pid\" 2>/dev/null; then alive=\"$alive $pid\"; fi; done; "
                + "if [ -z \"$alive\" ]; then break; fi; "
                + "sleep 1; i=$((i + 1)); "
                + "done; "
                + "if [ -n \"$alive\" ]; then echo \"QwenPaw app pids did not exit after SIGTERM:$alive\" >&2; exit 1; fi; "
                + "i=0; "
                + "while [ \"$i\" -lt 30 ]; do "
                + "restarted_pids=$(qwenpaw_pids); "
                + "if [ -n \"$restarted_pids\" ]; then echo \"QwenPaw app restarted with pids: $restarted_pids\"; exit 0; fi; "
                + "sleep 1; i=$((i + 1)); "
                + "done; "
                + "echo 'QwenPaw app did not restart after graceful SIGTERM' >&2; exit 1";
    }

    /**
     * 读取指定 ConfigMap 的 data 字段。
     */
    public Map<String, String> getConfigMap(String configMapName) {
        ConfigMap configMap = client.configMaps()
                .inNamespace(properties.getK8sNamespace())
                .withName(configMapName)
                .get();
        return configMap == null ? null : Optional.ofNullable(configMap.getData()).orElseGet(Map::of);
    }

    /**
     * 替换指定 ConfigMap 的 data 字段。
     */
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

    /**
     * 给控制器 Deployment 模板写入 restartedAt 注解，触发 Kubernetes 滚动重启。
     */
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

    /**
     * 更新用户 QwenPaw 运行时 ConfigMap，并滚动重启所有用户 Deployment。
     */
    public int refreshQwenPawRuntimeConfig(Map<String, String> data) {
        if (!updateConfigMap(properties.getQwenpawRuntimeConfigmapName(), data)) {
            return -1;
        }

        // restarted 统计本次被更新 Deployment 模板的数量。
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

    /**
     * 按名称读取 HTTPRoute。
     */
    private Optional<GenericKubernetesResource> getHttpRoute(String routeName) {
        GenericKubernetesResource route = client.genericKubernetesResources(HTTP_ROUTE_CONTEXT)
                .inNamespace(properties.getK8sNamespace())
                .withName(routeName)
                .get();
        return Optional.ofNullable(route);
    }

    /**
     * 把 Kubernetes Pod phase 映射为业务状态枚举。
     */
    private PodStatus mapPhaseToStatus(String phase) {
        return switch (phase == null ? "" : phase) {
            case "Running" -> PodStatus.RUNNING;
            case "Pending" -> PodStatus.PENDING;
            case "Failed" -> PodStatus.FAILED;
            default -> PodStatus.UNKNOWN;
        };
    }

    /**
     * 判断 Pod Ready condition 是否为 True。
     */
    private boolean isReady(Pod pod) {
        if (pod.getStatus() == null || pod.getStatus().getConditions() == null) {
            return false;
        }
        return pod.getStatus().getConditions().stream()
                .anyMatch(condition -> "Ready".equals(condition.getType()) && "True".equals(condition.getStatus()));
    }

    /**
     * 生成用户资源名称。
     */
    private String resourceName(String userId) {
        return "qwenpaw-" + userId;
    }

    /**
     * 转义用户 ID 中可能影响 HTTPRoute 正则匹配的字符。
     */
    private String escapeRegex(String value) {
        return value.replaceAll("([\\\\.\\[\\]{}()*+?^$|])", "\\\\$1");
    }

    /**
     * 把 exec 输出转成便于日志排查的短文本。
     */
    private String execOutput(ByteArrayOutputStream output) {
        String text = output.toString(StandardCharsets.UTF_8).strip();
        return text.length() <= 1000 ? text : text.substring(text.length() - 1000);
    }

    /**
     * 生成控制器管理资源时统一使用的标签。
     */
    private Map<String, String> managedLabels(String userId) {
        Map<String, String> labels = new HashMap<>();
        labels.put("app", properties.getQwenpawAppLabel());
        labels.put("user", userId);
        labels.put("managed-by", "qwenpaw-controller");
        return labels;
    }

    /**
     * 把 CPU 和内存字符串转换成 Kubernetes Quantity map。
     */
    private Map<String, Quantity> resourceMap(String cpu, String memory) {
        return Map.of("cpu", new Quantity(cpu), "memory", new Quantity(memory));
    }

    /**
     * 生成 initContainer 使用的卷挂载。
     */
    private List<VolumeMount> initVolumeMounts(String userId) {
        if (isSingleMountMode()) {
            // single-mount 模式下 initContainer 直接挂载整个 NAS 根目录。
            return List.of(volumeMount("nas-volume", properties.getQwenpawNasMountPath()));
        }
        // subPath 模式下 initContainer 同时挂载公共模板和该用户的三个数据目录。
        return List.of(
                volumeMount("template-volume", "/app/qwenpaw-public", properties.getQwenpawPublicTemplateSubPath(), true),
                volumeMount("data-volume", "/app/working", userId + "/working"),
                volumeMount("secrets-volume", "/app/working.secret", userId + "/working.secret"),
                volumeMount("backups-volume", "/app/working.backups", userId + "/working.backups"));
    }

    /**
     * 生成主 qwenpaw 业务容器使用的卷挂载。
     */
    private List<VolumeMount> qwenpawVolumeMounts(String userId) {
        if (isSingleMountMode()) {
            // single-mount 模式下业务容器也挂载整个 NAS，并通过环境变量指向用户目录。
            return List.of(
                    volumeMount("config-volume", "/app/config.yaml", "config.yaml"),
                    volumeMount("nas-volume", properties.getQwenpawNasMountPath()));
        }
        // subPath 模式下 /app/working 等路径直接就是该用户在 PVC 中的子目录。
        return List.of(
                volumeMount("config-volume", "/app/config.yaml", "config.yaml"),
                volumeMount("data-volume", "/app/working", userId + "/working"),
                volumeMount("secrets-volume", "/app/working.secret", userId + "/working.secret"),
                volumeMount("backups-volume", "/app/working.backups", userId + "/working.backups"));
    }

    /**
     * 声明 Pod 可用的卷，具体挂载路径由各容器的 volumeMounts 决定。
     */
    private List<Volume> podVolumes() {
        // configVolume 来自 ConfigMap，只把 config.yaml 挂到容器指定路径。
        Volume configVolume = new VolumeBuilder()
                .withName("config-volume")
                .withNewConfigMap()
                .withName(properties.getQwenpawConfigmapName())
                .endConfigMap()
                .build();
        if (isSingleMountMode()) {
            return List.of(configVolume, pvcVolume("nas-volume"));
        }
        // subPath 模式复用同一个 PVC，但用不同卷名表达不同挂载用途。
        return List.of(
                configVolume,
                pvcVolume("template-volume"),
                pvcVolume("data-volume"),
                pvcVolume("secrets-volume"),
                pvcVolume("backups-volume"));
    }

    /**
     * 判断当前是否使用 single-mount 挂载模式。
     */
    private boolean isSingleMountMode() {
        return "single-mount".equalsIgnoreCase(properties.getQwenpawVolumeMode());
    }

    /**
     * 确保用户 Deployment 带有运行时 ConfigMap envFrom，并写入重启注解。
     */
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
        // containers 是 Deployment Pod 模板里的业务容器列表。
        List<Container> containers = updated.getSpec().getTemplate().getSpec().getContainers();
        for (Container container : containers) {
            if ("qwenpaw".equals(container.getName()) && !hasRuntimeConfigEnvFrom(container)) {
                // envFrom 为空时直接设置，不为空时复制后追加，避免修改不可变列表。
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

    /**
     * 判断容器是否已经通过 envFrom 引用了运行时 ConfigMap。
     */
    private boolean hasRuntimeConfigEnvFrom(Container container) {
        List<EnvFromSource> envFrom = container.getEnvFrom();
        if (envFrom == null) {
            return false;
        }
        return envFrom.stream()
                .anyMatch(source -> source.getConfigMapRef() != null
                        && properties.getQwenpawRuntimeConfigmapName().equals(source.getConfigMapRef().getName()));
    }

    /**
     * 构造运行时 ConfigMap 的 envFrom 引用。
     */
    private EnvFromSource runtimeConfigEnvFrom() {
        return new EnvFromSourceBuilder()
                .withNewConfigMapRef(properties.getQwenpawRuntimeConfigmapName(), false)
                .build();
    }

    /**
     * 只保留每个用户 Pod 独有、无法放进全局 ConfigMap 的变量。
     */
    private List<EnvVar> qwenpawExplicitEnv(String userId) {
        List<EnvVar> env = new ArrayList<>();
        if (isSingleMountMode()) {
            env.add(new EnvVarBuilder().withName("QWENPAW_WORKING_DIR").withValue(userWorkingDir(userId)).build());
            env.add(new EnvVarBuilder().withName("QWENPAW_SECRET_DIR").withValue(userSecretDir(userId)).build());
        }
        env.add(new EnvVarBuilder().withName("USER_ID").withValue(userId).build());
        env.add(new EnvVarBuilder().withName("QWENPAW_USER").withValue(userId).build());
        return env;
    }

    /**
     * 构造带 subPath 和只读标记的卷挂载。
     */
    private io.fabric8.kubernetes.api.model.VolumeMount volumeMount(String name, String mountPath, String subPath, boolean readOnly) {
        return new VolumeMountBuilder()
                .withName(name)
                .withMountPath(mountPath)
                .withSubPath(subPath)
                .withReadOnly(readOnly)
                .build();
    }

    /**
     * 构造带 subPath 的卷挂载。
     */
    private io.fabric8.kubernetes.api.model.VolumeMount volumeMount(String name, String mountPath, String subPath) {
        return new VolumeMountBuilder()
                .withName(name)
                .withMountPath(mountPath)
                .withSubPath(subPath)
                .build();
    }

    /**
     * 构造不带 subPath 的卷挂载，通常用于挂载整个 PVC。
     */
    private io.fabric8.kubernetes.api.model.VolumeMount volumeMount(String name, String mountPath) {
        return new VolumeMountBuilder()
                .withName(name)
                .withMountPath(mountPath)
                .build();
    }

    /**
     * 构造引用 qwenpaw NAS PVC 的 Pod 卷。
     */
    private io.fabric8.kubernetes.api.model.Volume pvcVolume(String name) {
        return new VolumeBuilder()
                .withName(name)
                .withPersistentVolumeClaim(new PersistentVolumeClaimVolumeSourceBuilder()
                        .withClaimName(properties.getQwenpawNasPvcName())
                        .build())
                .build();
    }

    /**
     * 生成 initContainer 执行的初始化命令。
     */
    private String initConfigCommand(String userId) {
        // templateDir 是公共模板目录；single-mount 从 NAS 根目录拼路径，subPath 直接用挂载点。
        String templateDir = isSingleMountMode()
                ? properties.getQwenpawNasMountPath() + "/" + properties.getQwenpawPublicTemplateSubPath()
                : "/app/qwenpaw-public";
        // workingDir、secretDir、backupDir 是该用户持久化在 NAS 中的数据目录。
        String workingDir = userWorkingDir(userId);
        String secretDir = userSecretDir(userId);
        String backupDir = userBackupDir(userId);
        String personalApiKeyFile = secretDir + "/" + properties.getPersonalApiKeyFileRelativePath();
        String personalApiKeyTemplateFile = templateDir + "/working.secret/" + properties.getPersonalApiKeyFileRelativePath();
        // existingDataCheck 只要任意用户目录已有文件，就认为这是老用户数据并跳过模板复制。
        String existingDataCheck = "find " + shellQuote(workingDir) + " "
                + shellQuote(secretDir) + " " + shellQuote(backupDir)
                + " -mindepth 1 -print 2>/dev/null | head -n 1";
        String ensurePersonalApiKeyFile = "if [ ! -f " + shellQuote(personalApiKeyFile)
                + " ] && [ -f " + shellQuote(personalApiKeyTemplateFile) + " ]; then "
                + "mkdir -p \"$(dirname " + shellQuote(personalApiKeyFile) + ")\" && "
                + "cp -af " + shellQuote(personalApiKeyTemplateFile) + " " + shellQuote(personalApiKeyFile) + "; "
                + "fi";
        String fillPersonalApiKey = "if [ -f "
                + shellQuote(personalApiKeyFile) + " ]; then "
                + "if [ -n \"${PERSONAL_API_KEY:-}\" ]; then "
                + "escaped_key=$(printf '%s' \"$PERSONAL_API_KEY\" | sed 's/\\\\/\\\\\\\\/g; s/[\\/&]/\\\\&/g; s/\"/\\\\\"/g') && "
                + "sed -i \"s/\\\"api-key\\\"[[:space:]]*:[[:space:]]*\\\"[^\\\"]*\\\"/\\\"api-key\\\": \\\"${escaped_key}\\\"/\" "
                + shellQuote(personalApiKeyFile) + "; "
                + "fi; "
                + "if [ -n \"${ELLM_ADAPTER_BASE_URL:-}\" ]; then "
                + "escaped_base_url=$(printf '%s' \"$ELLM_ADAPTER_BASE_URL\" | sed 's/\\\\/\\\\\\\\/g; s/[\\/&]/\\\\&/g; s/\"/\\\\\"/g') && "
                + "sed -i \"s#\\\"base_url\\\"[[:space:]]*:[[:space:]]*\\\"[^\\\"]*\\\"#\\\"base_url\\\": \\\"${escaped_base_url}\\\"#\" "
                + shellQuote(personalApiKeyFile) + "; "
                + "fi; "
                + "fi";
        // initContainer 每次新 Pod 启动都会执行，但 cp 只在用户目录为空时发生。
        // personal-api-key.json 每次新建 Deployment 并拿到新 key 后都要覆盖为最新值。
        return "mkdir -p " + shellQuote(workingDir) + " " + shellQuote(secretDir) + " "
                + shellQuote(backupDir) + " && "
                + "if [ -n \"$(" + existingDataCheck + ")\" ]; then "
                + "echo 'QwenPaw user data exists; skip template copy'; "
                + "else "
                + "cp -af " + shellQuote(templateDir + "/working/.") + " " + shellQuote(workingDir + "/") + " && "
                + "cp -af " + shellQuote(templateDir + "/working.secret/.") + " " + shellQuote(secretDir + "/") + " && "
                + "if [ -d " + shellQuote(templateDir + "/working.backups") + " ]; then cp -af "
                + shellQuote(templateDir + "/working.backups/.") + " " + shellQuote(backupDir + "/") + "; fi; "
                + "fi && "
                + ensurePersonalApiKeyFile + " && "
                + fillPersonalApiKey;
    }

    /**
     * 把路径或参数转成安全的单引号 shell 字符串。
     */
    private String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    /**
     * 获取用户 working 目录在当前挂载模式下的容器内路径。
     */
    private String userWorkingDir(String userId) {
        return isSingleMountMode() ? properties.getQwenpawNasMountPath() + "/" + userId + "/working" : "/app/working";
    }

    /**
     * 获取用户 working.secret 目录在当前挂载模式下的容器内路径。
     */
    private String userSecretDir(String userId) {
        return isSingleMountMode() ? properties.getQwenpawNasMountPath() + "/" + userId + "/working.secret" : "/app/working.secret";
    }

    /**
     * 获取用户 working.backups 目录在当前挂载模式下的容器内路径。
     */
    private String userBackupDir(String userId) {
        return isSingleMountMode() ? properties.getQwenpawNasMountPath() + "/" + userId + "/working.backups" : "/app/working.backups";
    }

    /**
     * 等待下一次 Pod Ready 轮询。
     */
    private void sleep() {
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for pod readiness", e);
        }
    }
}
