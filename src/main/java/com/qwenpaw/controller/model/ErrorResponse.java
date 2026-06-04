package com.qwenpaw.controller.model;

/**
 * API 异常时返回给前端的错误信息。
 */
public class ErrorResponse {

    /**
     * 人类可读的错误描述。
     */
    private String error;

    /**
     * 程序可识别的错误码。
     */
    private String code;

    /**
     * 详细错误内容，通常来自异常消息。
     */
    private String details;

    /**
     * 创建只有错误描述的响应。
     */
    public ErrorResponse(String error) {
        this.error = error;
    }

    /**
     * 创建带错误码和详情的响应。
     */
    public ErrorResponse(String error, String code, String details) {
        this.error = error;
        this.code = code;
        this.details = details;
    }

    /**
     * 获取错误描述。
     */
    public String getError() {
        return error;
    }

    /**
     * 设置错误描述。
     */
    public void setError(String error) {
        this.error = error;
    }

    /**
     * 获取错误码。
     */
    public String getCode() {
        return code;
    }

    /**
     * 设置错误码。
     */
    public void setCode(String code) {
        this.code = code;
    }

    /**
     * 获取详细错误内容。
     */
    public String getDetails() {
        return details;
    }

    /**
     * 设置详细错误内容。
     */
    public void setDetails(String details) {
        this.details = details;
    }
}
