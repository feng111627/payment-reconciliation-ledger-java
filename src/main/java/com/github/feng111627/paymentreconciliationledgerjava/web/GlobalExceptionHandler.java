package com.github.feng111627.paymentreconciliationledgerjava.web;

import com.github.feng111627.paymentreconciliationledgerjava.error.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** 统一错误响应：业务错误都给出可区分的 code/message，不静默丢弃。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, String>> handle(BusinessException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("code", ex.getClass().getSimpleName(), "message", ex.getMessage()));
    }
}
