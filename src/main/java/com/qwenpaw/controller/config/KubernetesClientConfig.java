package com.qwenpaw.controller.config;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Kubernetes 客户端 Bean 配置。
 */
@Configuration
public class KubernetesClientConfig {

    /**
     * 创建 Fabric8 KubernetesClient，默认读取集群内 ServiceAccount 或本地 kubeconfig。
     */
    @Bean
    public KubernetesClient kubernetesClient() {
        return new KubernetesClientBuilder().build();
    }
}
