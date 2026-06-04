package com.qwenpaw.controller.model;

import java.time.OffsetDateTime;

/**
 * 用户 Pod 管理接口返回给前端的展示对象。
 */
public class UserPodResponse {

    /**
     * 业务用户 ID。
     */
    private String userId;

    /**
     * 当前实际运行的 Pod 名称；重启后通常会变化。
     */
    private String podName;

    /**
     * 管理用户 Pod 的 Deployment 名称。
     */
    private String deploymentName;

    /**
     * 暴露用户 Pod 的 Service 名称。
     */
    private String serviceName;

    /**
     * 将入口流量路由到用户 Service 的 HTTPRoute 名称。
     */
    private String httprouteName;

    /**
     * 用户实例的访问路径前缀。
     */
    private String pathPrefix;

    /**
     * 当前 Pod 状态。
     */
    private PodStatus status;

    /**
     * 前端可直接展示或跳转的访问地址。
     */
    private String accessUrl;

    /**
     * 本次操作的结果提示。
     */
    private String message;

    /**
     * Pod 或映射记录创建时间。
     */
    private OffsetDateTime createdAt;

    /**
     * Pod 或映射记录最后更新时间。
     */
    private OffsetDateTime updatedAt;

    /**
     * 将内部映射对象转换成接口响应对象。
     */
    public static UserPodResponse from(UserPodMapping mapping) {
        UserPodResponse response = new UserPodResponse();
        response.setUserId(mapping.getUserId());
        response.setPodName(mapping.getPodName());
        response.setDeploymentName(mapping.getDeploymentName());
        response.setServiceName(mapping.getServiceName());
        response.setHttprouteName(mapping.getHttprouteName());
        response.setPathPrefix(mapping.getPathPrefix());
        response.setStatus(mapping.getStatus());
        response.setAccessUrl(mapping.getPathPrefix());
        response.setCreatedAt(mapping.getCreatedAt());
        response.setUpdatedAt(mapping.getUpdatedAt());
        return response;
    }

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
     * 获取当前实际运行的 Pod 名称。
     */
    public String getPodName() {
        return podName;
    }

    /**
     * 设置当前实际运行的 Pod 名称。
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
     * 获取当前 Pod 状态。
     */
    public PodStatus getStatus() {
        return status;
    }

    /**
     * 设置当前 Pod 状态。
     */
    public void setStatus(PodStatus status) {
        this.status = status;
    }

    /**
     * 获取前端访问地址。
     */
    public String getAccessUrl() {
        return accessUrl;
    }

    /**
     * 设置前端访问地址。
     */
    public void setAccessUrl(String accessUrl) {
        this.accessUrl = accessUrl;
    }

    /**
     * 获取操作提示。
     */
    public String getMessage() {
        return message;
    }

    /**
     * 设置操作提示。
     */
    public void setMessage(String message) {
        this.message = message;
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
}
