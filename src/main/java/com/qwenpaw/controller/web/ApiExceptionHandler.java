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

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatus(ResponseStatusException exception) {
        return ResponseEntity
                .status(exception.getStatusCode())
                .body(new ErrorResponse(exception.getReason()));
    }

    @ExceptionHandler(KubernetesClientException.class)
    public ResponseEntity<ErrorResponse> handleKubernetesClient(KubernetesClientException exception) {
        log.error("Kubernetes API call failed", exception);
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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception exception) {
        log.error("Unhandled API error", exception);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("Internal server error", "INTERNAL_ERROR", exception.getMessage()));
    }

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
