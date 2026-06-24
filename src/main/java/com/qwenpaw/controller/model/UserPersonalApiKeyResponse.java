package com.qwenpaw.controller.model;

/**
 * 单个用户的 personal-api-key.json 内容和提取结果。
 */
public class UserPersonalApiKeyResponse {

    /**
     * 用户 ID。
     */
    private String userId;

    /**
     * personal-api-key.json 的完整路径。
     */
    private String filePath;

    /**
     * 文件原始 JSON 字符串。
     */
    private String json;

    /**
     * custom_headers.api-key 的值。
     */
    private String apiKey;

    /**
     * 文件是否存在。
     */
    private boolean exists;

    /**
     * 读取或解析失败时的错误信息。
     */
    private String error;

    /**
     * 获取用户 ID。
     */
    public String getUserId() {
        return userId;
    }

    /**
     * 设置用户 ID。
     */
    public void setUserId(String userId) {
        this.userId = userId;
    }

    /**
     * 获取文件路径。
     */
    public String getFilePath() {
        return filePath;
    }

    /**
     * 设置文件路径。
     */
    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    /**
     * 获取原始 JSON 字符串。
     */
    public String getJson() {
        return json;
    }

    /**
     * 设置原始 JSON 字符串。
     */
    public void setJson(String json) {
        this.json = json;
    }

    /**
     * 获取 custom_headers.api-key。
     */
    public String getApiKey() {
        return apiKey;
    }

    /**
     * 设置 custom_headers.api-key。
     */
    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    /**
     * 获取文件是否存在。
     */
    public boolean isExists() {
        return exists;
    }

    /**
     * 设置文件是否存在。
     */
    public void setExists(boolean exists) {
        this.exists = exists;
    }

    /**
     * 获取错误信息。
     */
    public String getError() {
        return error;
    }

    /**
     * 设置错误信息。
     */
    public void setError(String error) {
        this.error = error;
    }
}
