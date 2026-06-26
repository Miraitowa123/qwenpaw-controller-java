package com.qwenpaw.controller.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwenpaw.controller.config.QwenPawProperties;
import com.qwenpaw.controller.model.UserPersonalApiKeyResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * 读取 controller 挂载的 NAS 中各用户 personal-api-key.json。
 */
@Service
public class PersonalApiKeyFileService {

    /**
     * 读取日志。
     */
    private static final Logger log = LoggerFactory.getLogger(PersonalApiKeyFileService.class);

    /**
     * 配置项。
     */
    private final QwenPawProperties properties;

    /**
     * JSON 解析器。
     */
    private final ObjectMapper objectMapper;

    /**
     * 注入配置和 JSON 解析器。
     */
    public PersonalApiKeyFileService(QwenPawProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 读取指定用户的 personal-api-key.json。
     */
    public UserPersonalApiKeyResponse readPersonalApiKey(String userId) throws IOException {
        Path userDir = userDirectory(userId);
        return readUserPersonalApiKey(userDir);
    }

    /**
     * 列出 personalData 根目录下每个用户的 personal-api-key.json。
     */
    public List<UserPersonalApiKeyResponse> listPersonalApiKeys() throws IOException {
        Path personalDataRoot = personalDataRoot();
        if (!Files.isDirectory(personalDataRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new NoSuchFileException(personalDataRoot.toString());
        }

        try (Stream<Path> users = Files.list(personalDataRoot)) {
            return users
                    .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !properties.getQwenpawPublicTemplateSubPath().equals(path.getFileName().toString()))
                    .sorted((left, right) -> left.getFileName().toString().compareTo(right.getFileName().toString()))
                    .map(this::readUserPersonalApiKey)
                    .toList();
        }
    }

    /**
     * 读取单个用户的 personal-api-key.json。
     */
    private UserPersonalApiKeyResponse readUserPersonalApiKey(Path userDir) {
        UserPersonalApiKeyResponse response = new UserPersonalApiKeyResponse();
        String userId = userDir.getFileName().toString();
        response.setUserId(userId);

        Path userRoot = userDir.toAbsolutePath().normalize();
        Path file = userRoot.resolve("working.secret")
                .resolve(properties.getPersonalApiKeyFileRelativePath())
                .normalize();
        response.setFilePath(file.toString());

        if (!file.startsWith(userRoot)) {
            response.setError("personal-api-key.json path escapes user directory");
            return response;
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            response.setExists(false);
            return response;
        }

        response.setExists(true);
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            response.setJson(json);
            response.setApiKey(extractApiKey(json));
        } catch (IOException e) {
            log.warn("Failed to read personal-api-key.json for user {}", userId, e);
            response.setError("Failed to read file: " + e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Failed to parse personal-api-key.json for user {}", userId, e);
            response.setError("Failed to parse json: " + e.getMessage());
        }
        return response;
    }

    /**
     * 定位指定用户的目录并校验不越界。
     */
    private Path userDirectory(String userId) throws IOException {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId is required");
        }

        Path personalDataRoot = personalDataRoot();
        if (!Files.isDirectory(personalDataRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new NoSuchFileException(personalDataRoot.toString());
        }

        Path userSegment = Path.of(userId.trim()).normalize();
        if (userSegment.isAbsolute() || userSegment.getNameCount() != 1 || ".".equals(userSegment.toString()) || "..".equals(userSegment.toString())) {
            throw new IllegalArgumentException("invalid userId");
        }

        Path userDir = personalDataRoot.resolve(userSegment).normalize();
        if (!userDir.startsWith(personalDataRoot)) {
            throw new IllegalArgumentException("userId escapes personalData directory");
        }
        return userDir;
    }

    /**
     * 解析 custom_headers.api-key。
     */
    private String extractApiKey(String json) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(json);
        JsonNode customHeaders = root.path("custom_headers");
        if (customHeaders.isMissingNode() || customHeaders.isNull()) {
            return null;
        }
        JsonNode apiKeyNode = customHeaders.path("api-key");
        if (apiKeyNode.isMissingNode() || apiKeyNode.isNull()) {
            return null;
        }
        String value = apiKeyNode.asText();
        return value.isBlank() ? null : value;
    }

    /**
     * controller 挂载的 personalData 根目录。
     */
    private Path personalDataRoot() {
        return Path.of(properties.getPersonalDataMountPath()).toAbsolutePath().normalize();
    }
}
