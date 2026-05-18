package com.qwenpaw.controller;

import com.qwenpaw.controller.config.QwenPawProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(QwenPawProperties.class)
public class QwenPawControllerApplication {

    public static void main(String[] args) {
        SpringApplication.run(QwenPawControllerApplication.class, args);
    }
}
