package com.qwenpaw.controller.model;

import java.util.Map;

/**
 * 更新 ConfigMap 或运行时配置时前端提交的请求体。
 */
public class UpdateConfigRequest {

    /**
     * 要写入 ConfigMap 的键值数据。
     */
    private Map<String, String> data;

    /**
     * 写入配置后是否触发相关 Pod 或 Deployment 重启。
     */
    private boolean restartPods = true;

    /**
     * 获取要写入的配置数据。
     */
    public Map<String, String> getData() {
        return data;
    }

    /**
     * 设置要写入的配置数据。
     */
    public void setData(Map<String, String> data) {
        this.data = data;
    }

    /**
     * 判断更新配置后是否需要重启。
     */
    public boolean isRestartPods() {
        return restartPods;
    }

    /**
     * 设置更新配置后是否需要重启。
     */
    public void setRestartPods(boolean restartPods) {
        this.restartPods = restartPods;
    }
}
