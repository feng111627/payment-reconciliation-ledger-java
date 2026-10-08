package com.github.feng111627.paymentreconciliationledgerjava.error;

/** 对账动作冲突（如差异结论已被人工处置，禁止自动冲正覆盖）。 */
public class ReconciliationConflictException extends BusinessException {
    public ReconciliationConflictException(String message) {
        super(message);
    }
}
