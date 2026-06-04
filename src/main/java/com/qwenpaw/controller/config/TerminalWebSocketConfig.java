package com.qwenpaw.controller.config;

import com.qwenpaw.controller.web.TerminalWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 终端 WebSocket 路由配置。
 */
@Configuration
@EnableWebSocket
public class TerminalWebSocketConfig implements WebSocketConfigurer {

    /**
     * 处理浏览器终端与 Kubernetes exec 会话转发的处理器。
     */
    private final TerminalWebSocketHandler terminalWebSocketHandler;

    /**
     * 注入终端 WebSocket 处理器。
     */
    public TerminalWebSocketConfig(TerminalWebSocketHandler terminalWebSocketHandler) {
        this.terminalWebSocketHandler = terminalWebSocketHandler;
    }

    /**
     * 注册终端 WebSocket 地址。
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(terminalWebSocketHandler, "/api/v1/terminal")
                .setAllowedOriginPatterns("*");
    }
}
