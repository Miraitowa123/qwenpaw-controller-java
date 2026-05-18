package com.qwenpaw.controller.model;

import java.util.Map;

public class ConfigMapData {

    private String name;
    private String namespace;
    private Map<String, String> data;

    public ConfigMapData(String name, String namespace, Map<String, String> data) {
        this.name = name;
        this.namespace = namespace;
        this.data = data;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public Map<String, String> getData() {
        return data;
    }

    public void setData(Map<String, String> data) {
        this.data = data;
    }
}
