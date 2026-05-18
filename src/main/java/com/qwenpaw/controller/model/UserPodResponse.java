package com.qwenpaw.controller.model;

import java.time.OffsetDateTime;

public class UserPodResponse {

    private String userId;
    private String podName;
    private String deploymentName;
    private String serviceName;
    private String httprouteName;
    private String pathPrefix;
    private PodStatus status;
    private String accessUrl;
    private String message;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

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

    public String getAccessUrl() {
        return accessUrl;
    }

    public void setAccessUrl(String accessUrl) {
        this.accessUrl = accessUrl;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
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
}
