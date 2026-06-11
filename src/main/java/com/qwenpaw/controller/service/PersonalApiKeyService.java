package com.qwenpaw.controller.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qwenpaw.controller.config.QwenPawProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 负责为用户调用外部服务创建个人 API Key。
 */
@Service
public class PersonalApiKeyService {

    /**
     * 个人 API Key 创建日志。
     */
    private static final Logger log = LoggerFactory.getLogger(PersonalApiKeyService.class);

    /**
     * 响应中可能承载 API Key 的字段名，比较时会忽略大小写、中划线和下划线。
     */
    private static final Set<String> API_KEY_FIELD_NAMES = Set.of(
            "apikey",
            "personalapikey",
            "personalkey",
            "guipapikey");

    /**
     * qwenpaw.* 配置项。
     */
    private final QwenPawProperties properties;

    /**
     * JSON 构造和解析工具。
     */
    private final ObjectMapper objectMapper;

    /**
     * 调用外部 createPersonalApiKey 接口的 HTTP 客户端。
     */
    private final HttpClient httpClient;

    /**
     * 注入配置和 JSON 工具，并创建带超时的 HTTP 客户端。
     */
    public PersonalApiKeyService(QwenPawProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getPersonalApiKeyTimeoutSeconds()))
                .build();
    }

    /**
     * 调用 createPersonalApiKey 接口，返回当前用户的个人 API Key。
     */
    public String createPersonalApiKey(String userId, EllmEndpointResolver.EllmEndpoint ellmEndpoint) {
        try {
            String requestMessage = buildRequestMessage(userId);
            String requestBody = "REQ_MESSAGE=" + requestMessage;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ellmEndpoint.createPersonalApiKeyUrl()))
                    .timeout(Duration.ofSeconds(properties.getPersonalApiKeyTimeoutSeconds()))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Jumpcloud-Env", properties.getPersonalApiKeyJumpcloudEnv())
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("createPersonalApiKey failed with HTTP " + response.statusCode());
            }

            String apiKey = extractApiKey(response.body())
                    .orElseThrow(() -> new IllegalStateException("createPersonalApiKey response does not contain api-key"));
            log.info("Created personal API key for user {} in {}", userId, ellmEndpoint.runEnv());
            return apiKey;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while creating personal API key for user " + userId, e);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Failed to create personal API key for user " + userId, e);
        }
    }

    /**
     * 构造外部接口要求的 REQ_MESSAGE JSON 字符串。
     */
    private String buildRequestMessage(String userId) throws JsonProcessingException {
        ObjectNode root = objectMapper.createObjectNode();
        root.putObject("REQ_HEAD")
                .put("TRAN_PROCESS", "createPersonalApiKey");
        root.putObject("REQ_BODY")
                .putObject("param")
                .put("guip_userName", "")
                .put("guip_userID", userId);
        return objectMapper.writeValueAsString(root);
    }

    /**
     * 从接口响应体中递归查找 API Key 字段。
     */
    private Optional<String> extractApiKey(String responseBody) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(responseBody);
        return findApiKey(root);
    }

    /**
     * 在 JSON 对象或数组中递归查找疑似 API Key 的非空文本值。
     */
    private Optional<String> findApiKey(JsonNode node) {
        if (node == null || node.isNull()) {
            return Optional.empty();
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (isApiKeyField(field.getKey())) {
                    Optional<String> value = textValue(field.getValue());
                    if (value.isPresent()) {
                        return value;
                    }
                }
            }
            fields = node.fields();
            while (fields.hasNext()) {
                Optional<String> value = findApiKey(fields.next().getValue());
                if (value.isPresent()) {
                    return value;
                }
            }
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                Optional<String> value = findApiKey(item);
                if (value.isPresent()) {
                    return value;
                }
            }
        }
        return Optional.empty();
    }

    /**
     * 判断字段名是否可能表示 API Key。
     */
    private boolean isApiKeyField(String fieldName) {
        String normalized = fieldName == null ? "" : fieldName.replace("-", "").replace("_", "").toLowerCase();
        return API_KEY_FIELD_NAMES.contains(normalized);
    }

    /**
     * 提取非空文本值。
     */
    private Optional<String> textValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return Optional.empty();
        }
        String value = node.isTextual() ? node.asText() : node.asText(null);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value);
    }
}
