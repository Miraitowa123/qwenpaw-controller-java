package com.qwenpaw.controller.model;

import java.util.List;

/**
 * 模板同步请求。
 */
public class TemplateSyncRequest {

    /**
     * 选择要同步的模板相对路径。
     */
    private List<String> templatePaths;

    /**
     * 选择要下发的用户 ID。
     */
    private List<String> userIds;

    /**
     * 是否覆盖同名文件。
     */
    private boolean overwrite = true;

    /**
     * 获取模板路径列表。
     */
    public List<String> getTemplatePaths() {
        return templatePaths;
    }

    /**
     * 设置模板路径列表。
     */
    public void setTemplatePaths(List<String> templatePaths) {
        this.templatePaths = templatePaths;
    }

    /**
     * 获取用户 ID 列表。
     */
    public List<String> getUserIds() {
        return userIds;
    }

    /**
     * 设置用户 ID 列表。
     */
    public void setUserIds(List<String> userIds) {
        this.userIds = userIds;
    }

    /**
     * 获取是否覆盖同名文件。
     */
    public boolean isOverwrite() {
        return overwrite;
    }

    /**
     * 设置是否覆盖同名文件。
     */
    public void setOverwrite(boolean overwrite) {
        this.overwrite = overwrite;
    }
}
