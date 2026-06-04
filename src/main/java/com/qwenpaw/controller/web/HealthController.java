package com.qwenpaw.controller.web;

import com.qwenpaw.controller.model.HealthResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 应用健康检查和就绪检查接口。
 */
@RestController
public class HealthController {

    /**
     * 返回应用健康状态。
     */
    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse("healthy", "dev", null);
    }

    /**
     * 返回应用就绪状态。
     */
    @GetMapping("/ready")
    public HealthResponse ready() {
        return new HealthResponse("ready", "dev", Map.of("service", "ready"));
    }
}
