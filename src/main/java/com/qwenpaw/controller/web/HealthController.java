package com.qwenpaw.controller.web;

import com.qwenpaw.controller.model.HealthResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {

    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse("healthy", "dev", null);
    }

    @GetMapping("/ready")
    public HealthResponse ready() {
        return new HealthResponse("ready", "dev", Map.of("service", "ready"));
    }
}
