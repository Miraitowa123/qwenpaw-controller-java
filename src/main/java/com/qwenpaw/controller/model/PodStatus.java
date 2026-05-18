package com.qwenpaw.controller.model;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Set;

public enum PodStatus {
    PENDING("pending"),
    RUNNING("running"),
    FAILED("failed"),
    UNKNOWN("unknown"),
    TERMINATING("terminating"),
    CREATING("creating"),
    RESTARTING("restarting");

    private static final Set<PodStatus> RESTARTABLE = Set.of(FAILED, UNKNOWN, TERMINATING);

    private final String value;

    PodStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    public boolean isHealthy() {
        return this == RUNNING;
    }

    public boolean canRestart() {
        return RESTARTABLE.contains(this);
    }
}
