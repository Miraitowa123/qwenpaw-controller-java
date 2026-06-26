package com.qwenpaw.controller.model;

/**
 * 单个模板同步失败信息。
 */
public class TemplateSyncFailure {

    /**
     * 用户 ID。
     */
    private String userId;

    /**
     * 模板相对路径。
     */
    private String templatePath;

    /**
     * 目标路径。
     */
    private String targetPath;

    /**
     * 失败原因。
     */
    private String message;

    /**
     * 获取用户 ID。
     */
    public String getUserId() {
        return userId;
    }

    /**
     * 设置用户 ID。
     */
    public void setUserId(String userId) {
        this.userId = userId;
    }

    /**
     * 获取模板相对路径。
     */
    public String getTemplatePath() {
        return templatePath;
    }

    /**
     * 设置模板相对路径。
     */
    public void setTemplatePath(String templatePath) {
        this.templatePath = templatePath;
    }

    /**
     * 获取目标路径。
     */
    public String getTargetPath() {
        return targetPath;
    }

    /**
     * 设置目标路径。
     */
    public void setTargetPath(String targetPath) {
        this.targetPath = targetPath;
    }

    /**
     * 获取失败原因。
     */
    public String getMessage() {
        return message;
    }

    /**
     * 设置失败原因。
     */
    public void setMessage(String message) {
        this.message = message;
    }
}
