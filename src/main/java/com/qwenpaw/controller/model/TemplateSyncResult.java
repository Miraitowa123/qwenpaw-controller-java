package com.qwenpaw.controller.model;

import java.util.List;

/**
 * 模板同步执行结果。
 */
public class TemplateSyncResult {

    /**
     * 是否全部成功。
     */
    private boolean success;

    /**
     * 结果提示。
     */
    private String message;

    /**
     * 选中的模板路径。
     */
    private List<String> templatePaths;

    /**
     * 选中的用户列表。
     */
    private List<String> userIds;

    /**
     * 成功复制的文件数量。
     */
    private int copiedCount;

    /**
     * 跳过的文件数量。
     */
    private int skippedCount;

    /**
     * 失败明细。
     */
    private List<TemplateSyncFailure> failures;

    /**
     * 获取是否全部成功。
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * 设置是否全部成功。
     */
    public void setSuccess(boolean success) {
        this.success = success;
    }

    /**
     * 获取结果提示。
     */
    public String getMessage() {
        return message;
    }

    /**
     * 设置结果提示。
     */
    public void setMessage(String message) {
        this.message = message;
    }

    /**
     * 获取选中的模板路径。
     */
    public List<String> getTemplatePaths() {
        return templatePaths;
    }

    /**
     * 设置选中的模板路径。
     */
    public void setTemplatePaths(List<String> templatePaths) {
        this.templatePaths = templatePaths;
    }

    /**
     * 获取选中的用户列表。
     */
    public List<String> getUserIds() {
        return userIds;
    }

    /**
     * 设置选中的用户列表。
     */
    public void setUserIds(List<String> userIds) {
        this.userIds = userIds;
    }

    /**
     * 获取成功复制的文件数量。
     */
    public int getCopiedCount() {
        return copiedCount;
    }

    /**
     * 设置成功复制的文件数量。
     */
    public void setCopiedCount(int copiedCount) {
        this.copiedCount = copiedCount;
    }

    /**
     * 获取跳过的文件数量。
     */
    public int getSkippedCount() {
        return skippedCount;
    }

    /**
     * 设置跳过的文件数量。
     */
    public void setSkippedCount(int skippedCount) {
        this.skippedCount = skippedCount;
    }

    /**
     * 获取失败明细。
     */
    public List<TemplateSyncFailure> getFailures() {
        return failures;
    }

    /**
     * 设置失败明细。
     */
    public void setFailures(List<TemplateSyncFailure> failures) {
        this.failures = failures;
    }
}
