package com.qwenpaw.controller.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qwenpaw.controller.config.QwenPawProperties;
import com.qwenpaw.controller.model.UserProfile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 调用用户中心并只提取管理台需要的姓名和登录名。
 */
@Component
public class UserCenterClient {

    private final UserCenterEndpointResolver endpointResolver;
    private final QwenPawProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public UserCenterClient(UserCenterEndpointResolver endpointResolver,
                            QwenPawProperties properties,
                            ObjectMapper objectMapper) {
        this.endpointResolver = endpointResolver;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getUserCenterTimeoutSeconds()))
                .build();
    }

    /**
     * 按用户 ID 查询用户中心。响应中的其他个人信息不会进入本地模型或 Redis。
     */
    public UserProfile queryUserProfile(String userId) throws IOException, InterruptedException {
        ObjectNode requestJson = objectMapper.createObjectNode().put("userId", userId);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpointResolver.resolveQueryUserInfoUrl()))
                .timeout(Duration.ofSeconds(properties.getUserCenterTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(requestJson), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("queryUserInfo failed with HTTP " + response.statusCode());
        }

        JsonNode root = objectMapper.readTree(response.body());
        if (!root.path("success").asBoolean(false) || !root.path("data").isObject()) {
            throw new IllegalStateException(
                    "queryUserInfo failed: " + root.path("errCode").asText("") + " "
                            + root.path("errMsg").asText(""));
        }
        JsonNode data = root.path("data");
        return new UserProfile(nullableText(data, "userName"), nullableText(data, "loginName"));
    }

    private String nullableText(JsonNode node, String fieldName) {
        String value = node.path(fieldName).asText(null);
        return value == null || value.isBlank() ? null : value;
    }
}
