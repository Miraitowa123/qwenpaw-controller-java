package com.qwenpaw.controller.model;

import java.util.List;

/**
 * 模板同步页面需要的一次性数据。
 */
public class TemplateSyncOverviewResponse {

    /**
     * 模板根目录名称。
     */
    private String templateRoot;

    /**
     * 模板目录树。
     */
    private List<TemplateSyncNode> templateTree;

    /**
     * 可同步的用户列表。
     */
    private List<String> users;

    /**
     * 获取模板根目录名称。
     */
    public String getTemplateRoot() {
        return templateRoot;
    }

    /**
     * 设置模板根目录名称。
     */
    public void setTemplateRoot(String templateRoot) {
        this.templateRoot = templateRoot;
    }

    /**
     * 获取模板目录树。
     */
    public List<TemplateSyncNode> getTemplateTree() {
        return templateTree;
    }

    /**
     * 设置模板目录树。
     */
    public void setTemplateTree(List<TemplateSyncNode> templateTree) {
        this.templateTree = templateTree;
    }

    /**
     * 获取用户列表。
     */
    public List<String> getUsers() {
        return users;
    }

    /**
     * 设置用户列表。
     */
    public void setUsers(List<String> users) {
        this.users = users;
    }
}
