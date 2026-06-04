package com.qwenpaw.controller.model;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Set;

/**
 * 对外展示和内部判断使用的用户 Pod 状态。
 */
public enum PodStatus {
    /**
     * Pod 已创建但尚未运行或就绪。
     */
    PENDING("pending"),

    /**
     * Pod 已运行且可用。
     */
    RUNNING("running"),

    /**
     * Pod 已失败。
     */
    FAILED("failed"),

    /**
     * 无法从 Kubernetes 状态中明确判断。
     */
    UNKNOWN("unknown"),

    /**
     * Pod 正在终止。
     */
    TERMINATING("terminating"),

    /**
     * 控制器正在创建用户实例。
     */
    CREATING("creating"),

    /**
     * 控制器正在重启用户实例。
     */
    RESTARTING("restarting");

    /**
     * 可以尝试自动重启恢复的状态集合。
     */
    private static final Set<PodStatus> RESTARTABLE = Set.of(FAILED, UNKNOWN, TERMINATING);

    /**
     * 序列化给前端的状态字符串。
     */
    private final String value;

    /**
     * 创建状态枚举。
     */
    PodStatus(String value) {
        this.value = value;
    }

    /**
     * 将枚举序列化为前端使用的小写字符串。
     */
    @JsonValue
    public String getValue() {
        return value;
    }

    /**
     * 判断当前状态是否代表健康运行。
     */
    public boolean isHealthy() {
        return this == RUNNING;
    }

    /**
     * 判断当前状态是否适合由控制器尝试重启恢复。
     */
    public boolean canRestart() {
        return RESTARTABLE.contains(this);
    }
}
