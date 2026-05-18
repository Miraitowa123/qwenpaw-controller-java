package com.qwenpaw.controller.model;

import java.util.List;

public class UpdateConfigResponse {

    private boolean success;
    private String message;
    private List<String> updatedKeys;
    private boolean restarted;

    public UpdateConfigResponse(boolean success, String message, List<String> updatedKeys, boolean restarted) {
        this.success = success;
        this.message = message;
        this.updatedKeys = updatedKeys;
        this.restarted = restarted;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public List<String> getUpdatedKeys() {
        return updatedKeys;
    }

    public void setUpdatedKeys(List<String> updatedKeys) {
        this.updatedKeys = updatedKeys;
    }

    public boolean isRestarted() {
        return restarted;
    }

    public void setRestarted(boolean restarted) {
        this.restarted = restarted;
    }
}
