package com.qwenpaw.controller.model;

public class ErrorResponse {

    private String error;
    private String code;
    private String details;

    public ErrorResponse(String error) {
        this.error = error;
    }

    public ErrorResponse(String error, String code, String details) {
        this.error = error;
        this.code = code;
        this.details = details;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }
}
