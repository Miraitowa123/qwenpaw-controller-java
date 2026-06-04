package com.qwenpaw.controller;

import com.qwenpaw.controller.config.QwenPawProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * QwenPaw Controller 的 Spring Boot 启动入口。
 */
@SpringBootApplication
@EnableConfigurationProperties(QwenPawProperties.class)
public class QwenPawControllerApplication {

    /**
     * 启动 Spring Boot 应用。
     */
    public static void main(String[] args) {
        SpringApplication.run(QwenPawControllerApplication.class, args);
    }
}
