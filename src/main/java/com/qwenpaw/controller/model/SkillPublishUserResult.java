package com.qwenpaw.controller.model;

/**
 * 单个用户的技能发布结果。
 */
public class SkillPublishUserResult {

    private String userId;
    private String status;
    private String message;

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
