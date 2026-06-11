package com.qwenpaw.controller.service;

import com.qwenpaw.controller.config.QwenPawProperties;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * 根据 qwenpaw-runtime-config 中的 RUN_ENV 解析 ELLM 访问地址。
 */
@Component
public class EllmEndpointResolver {

    /**
     * RUN_ENV 到 ELLM 基础地址的映射。
     */
    private static final Map<String, String> ELLM_BASE_URLS = Map.of(
            "SIT", "http://12.234.162.238",
            "UAT", "http://12.244.66.225",
            "UAT2", "http://12.244.130.39",
            "UATC", "http://12.244.249.159",
            "PRD", "http://eaip-chn-slb-7006.bocomm.com");

    /**
     * Kubernetes 客户端，用于读取运行时 ConfigMap。
     */
    private final KubernetesClient client;

    /**
     * qwenpaw.* 配置项。
     */
    private final QwenPawProperties properties;

    /**
     * 注入 Kubernetes 客户端和配置对象。
     */
    public EllmEndpointResolver(KubernetesClient client, QwenPawProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /**
     * 读取 RUN_ENV 并解析出当前环境的 ELLM 端点。
     */
    public EllmEndpoint resolve() {
        ConfigMap configMap = client.configMaps()
                .inNamespace(properties.getK8sNamespace())
                .withName(properties.getQwenpawRuntimeConfigmapName())
                .get();
        Map<String, String> data = configMap == null ? Map.of() : Optional.ofNullable(configMap.getData()).orElseGet(Map::of);
        String runEnv = normalizeRunEnv(data.get("RUN_ENV"));
        if (runEnv.isBlank()) {
            throw new IllegalStateException("ConfigMap " + properties.getQwenpawRuntimeConfigmapName() + " missing RUN_ENV");
        }

        String baseUrl = ELLM_BASE_URLS.get(runEnv);
        if (baseUrl == null) {
            throw new IllegalStateException("Unsupported RUN_ENV " + runEnv + ", supported values are " + ELLM_BASE_URLS.keySet());
        }
        return new EllmEndpoint(runEnv, trimTrailingSlash(baseUrl));
    }

    /**
     * 规范化 RUN_ENV，避免大小写和空格导致匹配失败。
     */
    private String normalizeRunEnv(String runEnv) {
        return runEnv == null ? "" : runEnv.trim().toUpperCase();
    }

    /**
     * 去掉基础地址末尾多余斜杠，后续统一拼接路径。
     */
    private String trimTrailingSlash(String baseUrl) {
        String normalized = baseUrl;
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    /**
     * 当前 RUN_ENV 对应的一组 ELLM 端点。
     */
    public record EllmEndpoint(String runEnv, String baseUrl) {

        /**
         * 获取 createPersonalApiKey 接口地址。
         */
        public String createPersonalApiKeyUrl() {
            return baseUrl + "/ELLM.ELLM-OMSERVICE.V-1.0/createPersonalApiKey.do";
        }

        /**
         * 获取写入 personal-api-key.json 的 base_url。
         */
        public String adapterBaseUrl() {
            return baseUrl + "/ELLM.ELLM-ADAPTER.V-1.0/v1/";
        }
    }
}
