package com.qwenpaw.controller.model;

import java.time.OffsetDateTime;

/**
 * 内部使用的用户与 Kubernetes 资源映射。
 */
public class UserPodMapping {

    /**
     * 业务用户 ID。
     */
    private String userId;

    /**
     * 当前实际 Pod 名称；Deployment 重建 Pod 后会更新。
     */
    private String podName;

    /**
     * 管理用户实例的 Deployment 名称。
     */
    private String deploymentName;

    /**
     * 暴露用户实例的 Service 名称。
     */
    private String serviceName;

    /**
     * 网关路由资源名称。
     */
    private String httprouteName;

    /**
     * 用户实例的访问路径前缀。
     */
    private String pathPrefix;

    /**
     * 当前用户 Pod 状态。
     */
    private PodStatus status;

    /**
     * 映射首次创建或从 Pod 创建时间恢复出的时间。
     */
    private OffsetDateTime createdAt;

    /**
     * 映射最后一次状态更新时间。
     */
    private OffsetDateTime updatedAt;

    /**
     * 用户最后一次访问或被查询的时间。
     */
    private OffsetDateTime lastAccess;

    /**
     * 获取业务用户 ID。
     */
    public String getUserId() {
        return userId;
    }

    /**
     * 设置业务用户 ID。
     */
    public void setUserId(String userId) {
        this.userId = userId;
    }

    /**
     * 获取当前实际 Pod 名称。
     */
    public String getPodName() {
        return podName;
    }

    /**
     * 设置当前实际 Pod 名称。
     */
    public void setPodName(String podName) {
        this.podName = podName;
    }

    /**
     * 获取 Deployment 名称。
     */
    public String getDeploymentName() {
        return deploymentName;
    }

    /**
     * 设置 Deployment 名称。
     */
    public void setDeploymentName(String deploymentName) {
        this.deploymentName = deploymentName;
    }

    /**
     * 获取 Service 名称。
     */
    public String getServiceName() {
        return serviceName;
    }

    /**
     * 设置 Service 名称。
     */
    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    /**
     * 获取 HTTPRoute 名称。
     */
    public String getHttprouteName() {
        return httprouteName;
    }

    /**
     * 设置 HTTPRoute 名称。
     */
    public void setHttprouteName(String httprouteName) {
        this.httprouteName = httprouteName;
    }

    /**
     * 获取访问路径前缀。
     */
    public String getPathPrefix() {
        return pathPrefix;
    }

    /**
     * 设置访问路径前缀。
     */
    public void setPathPrefix(String pathPrefix) {
        this.pathPrefix = pathPrefix;
    }

    /**
     * 获取当前用户 Pod 状态。
     */
    public PodStatus getStatus() {
        return status;
    }

    /**
     * 设置当前用户 Pod 状态。
     */
    public void setStatus(PodStatus status) {
        this.status = status;
    }

    /**
     * 获取创建时间。
     */
    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    /**
     * 设置创建时间。
     */
    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    /**
     * 获取最后更新时间。
     */
    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    /**
     * 设置最后更新时间。
     */
    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    /**
     * 获取最后访问时间。
     */
    public OffsetDateTime getLastAccess() {
        return lastAccess;
    }

    /**
     * 设置最后访问时间。
     */
    public void setLastAccess(OffsetDateTime lastAccess) {
        this.lastAccess = lastAccess;
    }
}
