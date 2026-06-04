package com.qwenpaw.controller.model;

import java.util.List;

/**
 * 更新配置接口的统一响应。
 */
public class UpdateConfigResponse {

    /**
     * 配置更新是否成功。
     */
    private boolean success;

    /**
     * 展示给调用方的结果说明。
     */
    private String message;

    /**
     * 本次被更新的配置键。
     */
    private List<String> updatedKeys;

    /**
     * 是否已触发重启动作。
     */
    private boolean restarted;

    /**
     * 创建配置更新响应。
     */
    public UpdateConfigResponse(boolean success, String message, List<String> updatedKeys, boolean restarted) {
        this.success = success;
        this.message = message;
        this.updatedKeys = updatedKeys;
        this.restarted = restarted;
    }

    /**
     * 获取配置更新是否成功。
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * 设置配置更新是否成功。
     */
    public void setSuccess(boolean success) {
        this.success = success;
    }

    /**
     * 获取结果说明。
     */
    public String getMessage() {
        return message;
    }

    /**
     * 设置结果说明。
     */
    public void setMessage(String message) {
        this.message = message;
    }

    /**
     * 获取已更新的配置键。
     */
    public List<String> getUpdatedKeys() {
        return updatedKeys;
    }

    /**
     * 设置已更新的配置键。
     */
    public void setUpdatedKeys(List<String> updatedKeys) {
        this.updatedKeys = updatedKeys;
    }

    /**
     * 获取是否已触发重启动作。
     */
    public boolean isRestarted() {
        return restarted;
    }

    /**
     * 设置是否已触发重启动作。
     */
    public void setRestarted(boolean restarted) {
        this.restarted = restarted;
    }
}
