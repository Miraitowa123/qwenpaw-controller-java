package com.qwenpaw.controller.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * qwenpaw.* 配置项的绑定对象。
 */
@ConfigurationProperties(prefix = "qwenpaw")
public class QwenPawProperties {

    /**
     * 控制器管理用户 Pod 时使用的 Kubernetes 命名空间。
     */
    private String k8sNamespace = "ai";

    /**
     * 用户 QwenPaw Pod 使用的 app 标签值。
     */
    private String qwenpawAppLabel = "qwenpaw";

    /**
     * 用户 QwenPaw 业务容器镜像。
     */
    private String qwenpawImage = "docker.io/agentscope/qwenpaw:latest";

    /**
     * QwenPaw 业务容器监听端口。
     */
    private int qwenpawContainerPort = 8088;

    /**
     * 挂载到 QwenPaw 容器 /app/config.yaml 的 ConfigMap 名称。
     */
    private String qwenpawConfigmapName = "qwenpaw-global-config";

    /**
     * 通过 envFrom 注入用户 QwenPaw Pod 的运行时变量 ConfigMap 名称。
     */
    private String qwenpawRuntimeConfigmapName = "qwenpaw-runtime-config";

    /**
     * 存放模板和用户工作目录的 PVC 名称。
     */
    private String qwenpawNasPvcName = "qwenpaw-nas-pvc";

    /**
     * PVC 中公共模板目录的 subPath。
     */
    private String qwenpawPublicTemplateSubPath = "public-secret";

    /**
     * PVC 挂载模式：subpath 表示按用户目录分别挂载，single-mount 表示挂载整个 NAS 根目录。
     */
    private String qwenpawVolumeMode = "single-mount";

    /**
     * single-mount 模式下 PVC 在容器中的挂载根路径。
     */
    private String qwenpawNasMountPath = "/qwenpaw_nas";

    /**
     * 用户 QwenPaw 容器 CPU request。
     */
    private String resourceRequestsCpu = "500m";

    /**
     * 用户 QwenPaw 容器内存 request。
     */
    private String resourceRequestsMemory = "1Gi";

    /**
     * 用户 QwenPaw 容器 CPU limit。
     */
    private String resourceLimitsCpu = "1";

    /**
     * 用户 QwenPaw 容器内存 limit。
     */
    private String resourceLimitsMemory = "2Gi";

    /**
     * 存活探针首次执行前等待秒数。
     */
    private int livenessProbeInitialDelay = 30;

    /**
     * 存活探针执行周期秒数。
     */
    private int livenessProbePeriod = 10;

    /**
     * 存活探针超时秒数。
     */
    private int livenessProbeTimeout = 5;

    /**
     * 存活探针连续失败阈值。
     */
    private int livenessProbeFailureThreshold = 3;

    /**
     * 就绪探针首次执行前等待秒数。
     */
    private int readinessProbeInitialDelay = 10;

    /**
     * 就绪探针执行周期秒数。
     */
    private int readinessProbePeriod = 5;

    /**
     * 就绪探针超时秒数。
     */
    private int readinessProbeTimeout = 3;

    /**
     * 就绪探针连续失败阈值。
     */
    private int readinessProbeFailureThreshold = 2;

    /**
     * HTTPRoute 绑定的 Gateway 名称。
     */
    private String gatewayName = "traefik-gateway";

    /**
     * Gateway 所在命名空间。
     */
    private String gatewayNamespace = "ai";

    /**
     * 用户访问路径前缀。
     */
    private String basePathPrefix = "/users";

    /**
     * 控制器自身配置 ConfigMap 名称。
     */
    private String controllerConfigmapName = "qwenpaw-controller-config";

    /**
     * 控制器自身 Deployment 名称。
     */
    private String controllerDeploymentName = "qwenpaw-controller";

    /**
     * 等待用户 Pod Ready 的最长秒数。
     */
    private int podReadyTimeout = 600;

    /**
     * 控制器启动时是否扫描并同步已有用户 Pod 状态。
     */
    private boolean startupSyncEnabled = false;

    /**
     * 调用个人 API Key 接口时传递的 Jumpcloud-Env 请求头。
     */
    private String personalApiKeyJumpcloudEnv = "BASE";

    /**
     * 调用个人 API Key 接口的超时秒数。
     */
    private int personalApiKeyTimeoutSeconds = 20;

    /**
     * personal-api-key.json 在 working.secret 目录下的相对路径。
     */
    private String personalApiKeyFileRelativePath = "providers/custom/personal-api-key.json";

    /**
     * 获取 Kubernetes 命名空间。
     */
    public String getK8sNamespace() {
        return k8sNamespace;
    }

    /**
     * 设置 Kubernetes 命名空间。
     */
    public void setK8sNamespace(String k8sNamespace) {
        this.k8sNamespace = k8sNamespace;
    }

    /**
     * 获取用户 Pod app 标签值。
     */
    public String getQwenpawAppLabel() {
        return qwenpawAppLabel;
    }

    /**
     * 设置用户 Pod app 标签值。
     */
    public void setQwenpawAppLabel(String qwenpawAppLabel) {
        this.qwenpawAppLabel = qwenpawAppLabel;
    }

    /**
     * 获取 QwenPaw 业务容器镜像。
     */
    public String getQwenpawImage() {
        return qwenpawImage;
    }

    /**
     * 设置 QwenPaw 业务容器镜像。
     */
    public void setQwenpawImage(String qwenpawImage) {
        this.qwenpawImage = qwenpawImage;
    }

    /**
     * 获取 QwenPaw 业务容器端口。
     */
    public int getQwenpawContainerPort() {
        return qwenpawContainerPort;
    }

    /**
     * 设置 QwenPaw 业务容器端口。
     */
    public void setQwenpawContainerPort(int qwenpawContainerPort) {
        this.qwenpawContainerPort = qwenpawContainerPort;
    }

    /**
     * 获取全局配置 ConfigMap 名称。
     */
    public String getQwenpawConfigmapName() {
        return qwenpawConfigmapName;
    }

    /**
     * 设置全局配置 ConfigMap 名称。
     */
    public void setQwenpawConfigmapName(String qwenpawConfigmapName) {
        this.qwenpawConfigmapName = qwenpawConfigmapName;
    }

    /**
     * 获取运行时变量 ConfigMap 名称。
     */
    public String getQwenpawRuntimeConfigmapName() {
        return qwenpawRuntimeConfigmapName;
    }

    /**
     * 设置运行时变量 ConfigMap 名称。
     */
    public void setQwenpawRuntimeConfigmapName(String qwenpawRuntimeConfigmapName) {
        this.qwenpawRuntimeConfigmapName = qwenpawRuntimeConfigmapName;
    }

    /**
     * 获取 NAS PVC 名称。
     */
    public String getQwenpawNasPvcName() {
        return qwenpawNasPvcName;
    }

    /**
     * 设置 NAS PVC 名称。
     */
    public void setQwenpawNasPvcName(String qwenpawNasPvcName) {
        this.qwenpawNasPvcName = qwenpawNasPvcName;
    }

    /**
     * 获取公共模板目录 subPath。
     */
    public String getQwenpawPublicTemplateSubPath() {
        return qwenpawPublicTemplateSubPath;
    }

    /**
     * 设置公共模板目录 subPath。
     */
    public void setQwenpawPublicTemplateSubPath(String qwenpawPublicTemplateSubPath) {
        this.qwenpawPublicTemplateSubPath = qwenpawPublicTemplateSubPath;
    }

    /**
     * 获取 PVC 挂载模式。
     */
    public String getQwenpawVolumeMode() {
        return qwenpawVolumeMode;
    }

    /**
     * 设置 PVC 挂载模式。
     */
    public void setQwenpawVolumeMode(String qwenpawVolumeMode) {
        this.qwenpawVolumeMode = qwenpawVolumeMode;
    }

    /**
     * 获取 single-mount 模式下的 NAS 根路径。
     */
    public String getQwenpawNasMountPath() {
        return qwenpawNasMountPath;
    }

    /**
     * 设置 single-mount 模式下的 NAS 根路径。
     */
    public void setQwenpawNasMountPath(String qwenpawNasMountPath) {
        this.qwenpawNasMountPath = qwenpawNasMountPath;
    }

    /**
     * 获取 CPU request。
     */
    public String getResourceRequestsCpu() {
        return resourceRequestsCpu;
    }

    /**
     * 设置 CPU request。
     */
    public void setResourceRequestsCpu(String resourceRequestsCpu) {
        this.resourceRequestsCpu = resourceRequestsCpu;
    }

    /**
     * 获取内存 request。
     */
    public String getResourceRequestsMemory() {
        return resourceRequestsMemory;
    }

    /**
     * 设置内存 request。
     */
    public void setResourceRequestsMemory(String resourceRequestsMemory) {
        this.resourceRequestsMemory = resourceRequestsMemory;
    }

    /**
     * 获取 CPU limit。
     */
    public String getResourceLimitsCpu() {
        return resourceLimitsCpu;
    }

    /**
     * 设置 CPU limit。
     */
    public void setResourceLimitsCpu(String resourceLimitsCpu) {
        this.resourceLimitsCpu = resourceLimitsCpu;
    }

    /**
     * 获取内存 limit。
     */
    public String getResourceLimitsMemory() {
        return resourceLimitsMemory;
    }

    /**
     * 设置内存 limit。
     */
    public void setResourceLimitsMemory(String resourceLimitsMemory) {
        this.resourceLimitsMemory = resourceLimitsMemory;
    }

    /**
     * 获取存活探针初始延迟。
     */
    public int getLivenessProbeInitialDelay() {
        return livenessProbeInitialDelay;
    }

    /**
     * 设置存活探针初始延迟。
     */
    public void setLivenessProbeInitialDelay(int livenessProbeInitialDelay) {
        this.livenessProbeInitialDelay = livenessProbeInitialDelay;
    }

    /**
     * 获取存活探针执行周期秒数。
     */
    public int getLivenessProbePeriod() {
        return livenessProbePeriod;
    }

    /**
     * 设置存活探针执行周期秒数。
     */
    public void setLivenessProbePeriod(int livenessProbePeriod) {
        this.livenessProbePeriod = livenessProbePeriod;
    }

    /**
     * 获取存活探针超时秒数。
     */
    public int getLivenessProbeTimeout() {
        return livenessProbeTimeout;
    }

    /**
     * 设置存活探针超时秒数。
     */
    public void setLivenessProbeTimeout(int livenessProbeTimeout) {
        this.livenessProbeTimeout = livenessProbeTimeout;
    }

    /**
     * 获取存活探针连续失败阈值。
     */
    public int getLivenessProbeFailureThreshold() {
        return livenessProbeFailureThreshold;
    }

    /**
     * 设置存活探针连续失败阈值。
     */
    public void setLivenessProbeFailureThreshold(int livenessProbeFailureThreshold) {
        this.livenessProbeFailureThreshold = livenessProbeFailureThreshold;
    }

    /**
     * 获取就绪探针初始延迟秒数。
     */
    public int getReadinessProbeInitialDelay() {
        return readinessProbeInitialDelay;
    }

    /**
     * 设置就绪探针初始延迟秒数。
     */
    public void setReadinessProbeInitialDelay(int readinessProbeInitialDelay) {
        this.readinessProbeInitialDelay = readinessProbeInitialDelay;
    }

    /**
     * 获取就绪探针执行周期秒数。
     */
    public int getReadinessProbePeriod() {
        return readinessProbePeriod;
    }

    /**
     * 设置就绪探针执行周期秒数。
     */
    public void setReadinessProbePeriod(int readinessProbePeriod) {
        this.readinessProbePeriod = readinessProbePeriod;
    }

    /**
     * 获取就绪探针超时秒数。
     */
    public int getReadinessProbeTimeout() {
        return readinessProbeTimeout;
    }

    /**
     * 设置就绪探针超时秒数。
     */
    public void setReadinessProbeTimeout(int readinessProbeTimeout) {
        this.readinessProbeTimeout = readinessProbeTimeout;
    }

    /**
     * 获取就绪探针连续失败阈值。
     */
    public int getReadinessProbeFailureThreshold() {
        return readinessProbeFailureThreshold;
    }

    /**
     * 设置就绪探针连续失败阈值。
     */
    public void setReadinessProbeFailureThreshold(int readinessProbeFailureThreshold) {
        this.readinessProbeFailureThreshold = readinessProbeFailureThreshold;
    }

    /**
     * 获取 HTTPRoute 绑定的 Gateway 名称。
     */
    public String getGatewayName() {
        return gatewayName;
    }

    /**
     * 设置 HTTPRoute 绑定的 Gateway 名称。
     */
    public void setGatewayName(String gatewayName) {
        this.gatewayName = gatewayName;
    }

    /**
     * 获取 Gateway 所在命名空间。
     */
    public String getGatewayNamespace() {
        return gatewayNamespace;
    }

    /**
     * 设置 Gateway 所在命名空间。
     */
    public void setGatewayNamespace(String gatewayNamespace) {
        this.gatewayNamespace = gatewayNamespace;
    }

    /**
     * 获取用户访问路径前缀。
     */
    public String getBasePathPrefix() {
        return basePathPrefix;
    }

    /**
     * 设置用户访问路径前缀。
     */
    public void setBasePathPrefix(String basePathPrefix) {
        this.basePathPrefix = basePathPrefix;
    }

    /**
     * 获取控制器自身 ConfigMap 名称。
     */
    public String getControllerConfigmapName() {
        return controllerConfigmapName;
    }

    /**
     * 设置控制器自身 ConfigMap 名称。
     */
    public void setControllerConfigmapName(String controllerConfigmapName) {
        this.controllerConfigmapName = controllerConfigmapName;
    }

    /**
     * 获取控制器自身 Deployment 名称。
     */
    public String getControllerDeploymentName() {
        return controllerDeploymentName;
    }

    /**
     * 设置控制器自身 Deployment 名称。
     */
    public void setControllerDeploymentName(String controllerDeploymentName) {
        this.controllerDeploymentName = controllerDeploymentName;
    }

    /**
     * 获取等待用户 Pod Ready 的最长秒数。
     */
    public int getPodReadyTimeout() {
        return podReadyTimeout;
    }

    /**
     * 设置等待用户 Pod Ready 的最长秒数。
     */
    public void setPodReadyTimeout(int podReadyTimeout) {
        this.podReadyTimeout = podReadyTimeout;
    }

    /**
     * 获取控制器启动时是否同步已有用户 Pod。
     */
    public boolean isStartupSyncEnabled() {
        return startupSyncEnabled;
    }

    /**
     * 设置控制器启动时是否同步已有用户 Pod。
     */
    public void setStartupSyncEnabled(boolean startupSyncEnabled) {
        this.startupSyncEnabled = startupSyncEnabled;
    }

    /**
     * 获取调用个人 API Key 接口时使用的 Jumpcloud-Env 请求头。
     */
    public String getPersonalApiKeyJumpcloudEnv() {
        return personalApiKeyJumpcloudEnv;
    }

    /**
     * 设置调用个人 API Key 接口时使用的 Jumpcloud-Env 请求头。
     */
    public void setPersonalApiKeyJumpcloudEnv(String personalApiKeyJumpcloudEnv) {
        this.personalApiKeyJumpcloudEnv = personalApiKeyJumpcloudEnv;
    }

    /**
     * 获取调用个人 API Key 接口的超时秒数。
     */
    public int getPersonalApiKeyTimeoutSeconds() {
        return personalApiKeyTimeoutSeconds;
    }

    /**
     * 设置调用个人 API Key 接口的超时秒数。
     */
    public void setPersonalApiKeyTimeoutSeconds(int personalApiKeyTimeoutSeconds) {
        this.personalApiKeyTimeoutSeconds = personalApiKeyTimeoutSeconds;
    }

    /**
     * 获取 personal-api-key.json 在 working.secret 下的相对路径。
     */
    public String getPersonalApiKeyFileRelativePath() {
        return personalApiKeyFileRelativePath;
    }

    /**
     * 设置 personal-api-key.json 在 working.secret 下的相对路径。
     */
    public void setPersonalApiKeyFileRelativePath(String personalApiKeyFileRelativePath) {
        this.personalApiKeyFileRelativePath = personalApiKeyFileRelativePath;
    }
}
