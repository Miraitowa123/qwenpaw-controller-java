package com.qwenpaw.controller.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "qwenpaw")
public class QwenPawProperties {

    private String k8sNamespace = "ai";
    private String qwenpawAppLabel = "qwenpaw";
    private String qwenpawImage = "docker.io/agentscope/qwenpaw:latest";
    private int qwenpawContainerPort = 8088;
    private String qwenpawConfigmapName = "qwenpaw-global-config";
    private String qwenpawRuntimeConfigmapName = "qwenpaw-runtime-config";
    private String qwenpawNasPvcName = "qwenpaw-nas-pvc";
    private String qwenpawPublicTemplateSubPath = "public-secret";
    private String resourceRequestsCpu = "500m";
    private String resourceRequestsMemory = "1Gi";
    private String resourceLimitsCpu = "1";
    private String resourceLimitsMemory = "2Gi";
    private int livenessProbeInitialDelay = 30;
    private int livenessProbePeriod = 10;
    private int livenessProbeTimeout = 5;
    private int livenessProbeFailureThreshold = 3;
    private int readinessProbeInitialDelay = 10;
    private int readinessProbePeriod = 5;
    private int readinessProbeTimeout = 3;
    private int readinessProbeFailureThreshold = 2;
    private String gatewayName = "traefik-gateway";
    private String gatewayNamespace = "ai";
    private String basePathPrefix = "/users";
    private String controllerConfigmapName = "qwenpaw-controller-config";
    private String controllerDeploymentName = "qwenpaw-controller";
    private int podReadyTimeout = 600;
    private boolean startupSyncEnabled = false;

    public String getK8sNamespace() {
        return k8sNamespace;
    }

    public void setK8sNamespace(String k8sNamespace) {
        this.k8sNamespace = k8sNamespace;
    }

    public String getQwenpawAppLabel() {
        return qwenpawAppLabel;
    }

    public void setQwenpawAppLabel(String qwenpawAppLabel) {
        this.qwenpawAppLabel = qwenpawAppLabel;
    }

    public String getQwenpawImage() {
        return qwenpawImage;
    }

    public void setQwenpawImage(String qwenpawImage) {
        this.qwenpawImage = qwenpawImage;
    }

    public int getQwenpawContainerPort() {
        return qwenpawContainerPort;
    }

    public void setQwenpawContainerPort(int qwenpawContainerPort) {
        this.qwenpawContainerPort = qwenpawContainerPort;
    }

    public String getQwenpawConfigmapName() {
        return qwenpawConfigmapName;
    }

    public void setQwenpawConfigmapName(String qwenpawConfigmapName) {
        this.qwenpawConfigmapName = qwenpawConfigmapName;
    }

    public String getQwenpawRuntimeConfigmapName() {
        return qwenpawRuntimeConfigmapName;
    }

    public void setQwenpawRuntimeConfigmapName(String qwenpawRuntimeConfigmapName) {
        this.qwenpawRuntimeConfigmapName = qwenpawRuntimeConfigmapName;
    }

    public String getQwenpawNasPvcName() {
        return qwenpawNasPvcName;
    }

    public void setQwenpawNasPvcName(String qwenpawNasPvcName) {
        this.qwenpawNasPvcName = qwenpawNasPvcName;
    }

    public String getQwenpawPublicTemplateSubPath() {
        return qwenpawPublicTemplateSubPath;
    }

    public void setQwenpawPublicTemplateSubPath(String qwenpawPublicTemplateSubPath) {
        this.qwenpawPublicTemplateSubPath = qwenpawPublicTemplateSubPath;
    }

    public String getResourceRequestsCpu() {
        return resourceRequestsCpu;
    }

    public void setResourceRequestsCpu(String resourceRequestsCpu) {
        this.resourceRequestsCpu = resourceRequestsCpu;
    }

    public String getResourceRequestsMemory() {
        return resourceRequestsMemory;
    }

    public void setResourceRequestsMemory(String resourceRequestsMemory) {
        this.resourceRequestsMemory = resourceRequestsMemory;
    }

    public String getResourceLimitsCpu() {
        return resourceLimitsCpu;
    }

    public void setResourceLimitsCpu(String resourceLimitsCpu) {
        this.resourceLimitsCpu = resourceLimitsCpu;
    }

    public String getResourceLimitsMemory() {
        return resourceLimitsMemory;
    }

    public void setResourceLimitsMemory(String resourceLimitsMemory) {
        this.resourceLimitsMemory = resourceLimitsMemory;
    }

    public int getLivenessProbeInitialDelay() {
        return livenessProbeInitialDelay;
    }

    public void setLivenessProbeInitialDelay(int livenessProbeInitialDelay) {
        this.livenessProbeInitialDelay = livenessProbeInitialDelay;
    }

    public int getLivenessProbePeriod() {
        return livenessProbePeriod;
    }

    public void setLivenessProbePeriod(int livenessProbePeriod) {
        this.livenessProbePeriod = livenessProbePeriod;
    }

    public int getLivenessProbeTimeout() {
        return livenessProbeTimeout;
    }

    public void setLivenessProbeTimeout(int livenessProbeTimeout) {
        this.livenessProbeTimeout = livenessProbeTimeout;
    }

    public int getLivenessProbeFailureThreshold() {
        return livenessProbeFailureThreshold;
    }

    public void setLivenessProbeFailureThreshold(int livenessProbeFailureThreshold) {
        this.livenessProbeFailureThreshold = livenessProbeFailureThreshold;
    }

    public int getReadinessProbeInitialDelay() {
        return readinessProbeInitialDelay;
    }

    public void setReadinessProbeInitialDelay(int readinessProbeInitialDelay) {
        this.readinessProbeInitialDelay = readinessProbeInitialDelay;
    }

    public int getReadinessProbePeriod() {
        return readinessProbePeriod;
    }

    public void setReadinessProbePeriod(int readinessProbePeriod) {
        this.readinessProbePeriod = readinessProbePeriod;
    }

    public int getReadinessProbeTimeout() {
        return readinessProbeTimeout;
    }

    public void setReadinessProbeTimeout(int readinessProbeTimeout) {
        this.readinessProbeTimeout = readinessProbeTimeout;
    }

    public int getReadinessProbeFailureThreshold() {
        return readinessProbeFailureThreshold;
    }

    public void setReadinessProbeFailureThreshold(int readinessProbeFailureThreshold) {
        this.readinessProbeFailureThreshold = readinessProbeFailureThreshold;
    }

    public String getGatewayName() {
        return gatewayName;
    }

    public void setGatewayName(String gatewayName) {
        this.gatewayName = gatewayName;
    }

    public String getGatewayNamespace() {
        return gatewayNamespace;
    }

    public void setGatewayNamespace(String gatewayNamespace) {
        this.gatewayNamespace = gatewayNamespace;
    }

    public String getBasePathPrefix() {
        return basePathPrefix;
    }

    public void setBasePathPrefix(String basePathPrefix) {
        this.basePathPrefix = basePathPrefix;
    }

    public String getControllerConfigmapName() {
        return controllerConfigmapName;
    }

    public void setControllerConfigmapName(String controllerConfigmapName) {
        this.controllerConfigmapName = controllerConfigmapName;
    }

    public String getControllerDeploymentName() {
        return controllerDeploymentName;
    }

    public void setControllerDeploymentName(String controllerDeploymentName) {
        this.controllerDeploymentName = controllerDeploymentName;
    }

    public int getPodReadyTimeout() {
        return podReadyTimeout;
    }

    public void setPodReadyTimeout(int podReadyTimeout) {
        this.podReadyTimeout = podReadyTimeout;
    }

    public boolean isStartupSyncEnabled() {
        return startupSyncEnabled;
    }

    public void setStartupSyncEnabled(boolean startupSyncEnabled) {
        this.startupSyncEnabled = startupSyncEnabled;
    }
}
