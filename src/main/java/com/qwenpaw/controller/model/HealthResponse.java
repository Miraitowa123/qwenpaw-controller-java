package com.qwenpaw.controller.model;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 健康检查和就绪检查接口的统一响应。
 */
public class HealthResponse {

    /**
     * 当前健康状态，例如 healthy 或 ready。
     */
    private String status;

    /**
     * 生成响应时的时间戳。
     */
    private OffsetDateTime timestamp;

    /**
     * 应用版本号。
     */
    private String version;

    /**
     * 细分检查项结果。
     */
    private Map<String, String> checks;

    /**
     * 创建健康检查响应，并自动填充当前时间。
     */
    public HealthResponse(String status, String version, Map<String, String> checks) {
        this.status = status;
        this.timestamp = OffsetDateTime.now();
        this.version = version;
        this.checks = checks;
    }

    /**
     * 获取当前健康状态。
     */
    public String getStatus() {
        return status;
    }

    /**
     * 设置当前健康状态。
     */
    public void setStatus(String status) {
        this.status = status;
    }

    /**
     * 获取响应时间戳。
     */
    public OffsetDateTime getTimestamp() {
        return timestamp;
    }

    /**
     * 设置响应时间戳。
     */
    public void setTimestamp(OffsetDateTime timestamp) {
        this.timestamp = timestamp;
    }

    /**
     * 获取应用版本号。
     */
    public String getVersion() {
        return version;
    }

    /**
     * 设置应用版本号。
     */
    public void setVersion(String version) {
        this.version = version;
    }

    /**
     * 获取细分检查项结果。
     */
    public Map<String, String> getChecks() {
        return checks;
    }

    /**
     * 设置细分检查项结果。
     */
    public void setChecks(Map<String, String> checks) {
        this.checks = checks;
    }
}
