package com.qwenpaw.controller.model;

import java.util.Map;

/**
 * 返回给前端展示和编辑的 Kubernetes ConfigMap 数据。
 */
public class ConfigMapData {

    /**
     * ConfigMap 名称。
     */
    private String name;

    /**
     * ConfigMap 所在命名空间。
     */
    private String namespace;

    /**
     * ConfigMap 中的键值数据。
     */
    private Map<String, String> data;

    /**
     * 创建 ConfigMap 响应对象。
     */
    public ConfigMapData(String name, String namespace, Map<String, String> data) {
        this.name = name;
        this.namespace = namespace;
        this.data = data;
    }

    /**
     * 获取 ConfigMap 名称。
     */
    public String getName() {
        return name;
    }

    /**
     * 设置 ConfigMap 名称。
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * 获取 ConfigMap 所在命名空间。
     */
    public String getNamespace() {
        return namespace;
    }

    /**
     * 设置 ConfigMap 所在命名空间。
     */
    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    /**
     * 获取 ConfigMap 键值数据。
     */
    public Map<String, String> getData() {
        return data;
    }

    /**
     * 设置 ConfigMap 键值数据。
     */
    public void setData(Map<String, String> data) {
        this.data = data;
    }
}
