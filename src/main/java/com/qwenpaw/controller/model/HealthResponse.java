package com.qwenpaw.controller.model;

import java.time.OffsetDateTime;
import java.util.Map;

public class HealthResponse {

    private String status;
    private OffsetDateTime timestamp;
    private String version;
    private Map<String, String> checks;

    public HealthResponse(String status, String version, Map<String, String> checks) {
        this.status = status;
        this.timestamp = OffsetDateTime.now();
        this.version = version;
        this.checks = checks;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public OffsetDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(OffsetDateTime timestamp) {
        this.timestamp = timestamp;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public Map<String, String> getChecks() {
        return checks;
    }

    public void setChecks(Map<String, String> checks) {
        this.checks = checks;
    }
}
