package com.qwenpaw.controller.model;

import java.util.List;

/**
 * 模板目录树中的一个节点。
 */
public class TemplateSyncNode {

    /**
     * 节点名称。
     */
    private String name;

    /**
     * 相对模板根目录的路径。
     */
    private String relativePath;

    /**
     * 是否为目录。
     */
    private boolean directory;

    /**
     * 子节点。
     */
    private List<TemplateSyncNode> children;

    /**
     * 获取节点名称。
     */
    public String getName() {
        return name;
    }

    /**
     * 设置节点名称。
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * 获取相对路径。
     */
    public String getRelativePath() {
        return relativePath;
    }

    /**
     * 设置相对路径。
     */
    public void setRelativePath(String relativePath) {
        this.relativePath = relativePath;
    }

    /**
     * 获取是否为目录。
     */
    public boolean isDirectory() {
        return directory;
    }

    /**
     * 设置是否为目录。
     */
    public void setDirectory(boolean directory) {
        this.directory = directory;
    }

    /**
     * 获取子节点。
     */
    public List<TemplateSyncNode> getChildren() {
        return children;
    }

    /**
     * 设置子节点。
     */
    public void setChildren(List<TemplateSyncNode> children) {
        this.children = children;
    }
}
