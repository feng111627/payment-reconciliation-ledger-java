package com.github.feng111627.paymentreconciliationledgerjava.error;

/** 同一幂等键重复提交但请求指纹不同：不能当成同一次请求。 */
public class IdempotencyConflictException extends BusinessException {
    public IdempotencyConflictException(String message) {
        super(message);
    }
}
