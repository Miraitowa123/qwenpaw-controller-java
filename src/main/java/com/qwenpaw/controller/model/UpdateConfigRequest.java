package com.qwenpaw.controller.model;

import java.util.Map;

public class UpdateConfigRequest {

    private Map<String, String> data;
    private boolean restartPods = true;

    public Map<String, String> getData() {
        return data;
    }

    public void setData(Map<String, String> data) {
        this.data = data;
    }

    public boolean isRestartPods() {
        return restartPods;
    }

    public void setRestartPods(boolean restartPods) {
        this.restartPods = restartPods;
    }
}
