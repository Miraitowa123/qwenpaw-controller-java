package com.qwenpaw.controller.model;

import java.time.OffsetDateTime;

public class UserPodMapping {

    private String userId;
    private String podName;
    private String deploymentName;
    private String serviceName;
    private String httprouteName;
    private String pathPrefix;
    private PodStatus status;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime lastAccess;

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getPodName() {
        return podName;
    }

    public void setPodName(String podName) {
        this.podName = podName;
    }

    public String getDeploymentName() {
        return deploymentName;
    }

    public void setDeploymentName(String deploymentName) {
        this.deploymentName = deploymentName;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getHttprouteName() {
        return httprouteName;
    }

    public void setHttprouteName(String httprouteName) {
        this.httprouteName = httprouteName;
    }

    public String getPathPrefix() {
        return pathPrefix;
    }

    public void setPathPrefix(String pathPrefix) {
        this.pathPrefix = pathPrefix;
    }

    public PodStatus getStatus() {
        return status;
    }

    public void setStatus(PodStatus status) {
        this.status = status;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public OffsetDateTime getLastAccess() {
        return lastAccess;
    }

    public void setLastAccess(OffsetDateTime lastAccess) {
        this.lastAccess = lastAccess;
    }
}
