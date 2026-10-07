package com.github.feng111627.paymentreconciliationledgerjava.controller;

import com.github.feng111627.paymentreconciliationledgerjava.domain.IllegalTransitionException;
import com.github.feng111627.paymentreconciliationledgerjava.domain.PaymentRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** 把可区分的拒绝原因映射为明确的 HTTP 响应。 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(PaymentRejectedException.class)
    public ResponseEntity<Map<String, Object>> handleRejected(PaymentRejectedException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of("error", ex.getReason().name(), "message", ex.getMessage()));
    }

    @ExceptionHandler(IllegalTransitionException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalTransition(IllegalTransitionException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "ILLEGAL_STATE_TRANSITION", "message", ex.getMessage()));
    }
}
