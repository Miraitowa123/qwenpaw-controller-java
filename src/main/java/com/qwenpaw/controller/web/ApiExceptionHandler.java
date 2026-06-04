package com.qwenpaw.controller.web;

import com.qwenpaw.controller.model.ErrorResponse;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 统一把 Controller 抛出的异常转换成前端可读的 JSON 错误响应。
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    /**
     * 异常处理日志。
     */
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * 处理业务接口主动抛出的 HTTP 状态异常。
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatus(ResponseStatusException exception) {
        return ResponseEntity
                .status(exception.getStatusCode())
                .body(new ErrorResponse(exception.getReason()));
    }

    /**
     * 处理 Kubernetes API 调用失败，尽量保留 Kubernetes 返回的 HTTP 状态码。
     */
    @ExceptionHandler(KubernetesClientException.class)
    public ResponseEntity<ErrorResponse> handleKubernetesClient(KubernetesClientException exception) {
        log.error("Kubernetes API call failed", exception);
        // KubernetesClientException#getCode 可能为 0，需要兜底为 500。
        HttpStatus status = exception.getCode() > 0
                ? HttpStatus.resolve(exception.getCode())
                : HttpStatus.INTERNAL_SERVER_ERROR;
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return ResponseEntity
                .status(status)
                .body(new ErrorResponse("Kubernetes API call failed", "KUBERNETES_ERROR", exception.getMessage()));
    }

    /**
     * 处理未被业务分支捕获的异常，避免把默认错误页返回给 API 调用方。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception exception) {
        log.error("Unhandled API error", exception);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("Internal server error", "INTERNAL_ERROR", exception.getMessage()));
    }

    /**
     * 覆盖 Spring MVC 内部异常响应，统一错误体格式。
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex,
                                                            Object body,
                                                            HttpHeaders headers,
                                                            HttpStatusCode statusCode,
                                                            WebRequest request) {
        return ResponseEntity
                .status(statusCode)
                .headers(headers)
                .body(new ErrorResponse(ex.getMessage()));
    }
}
