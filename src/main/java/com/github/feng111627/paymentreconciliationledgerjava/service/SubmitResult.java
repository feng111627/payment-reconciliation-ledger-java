package com.github.feng111627.paymentreconciliationledgerjava.service;

import com.github.feng111627.paymentreconciliationledgerjava.domain.PaymentStatus;

/** 受理结果：明确区分“新受理”与“幂等命中重复提交”。 */
public record SubmitResult(String idempotencyKey, PaymentStatus status, boolean duplicate) {

    public static SubmitResult accepted(String key, PaymentStatus status) {
        return new SubmitResult(key, status, false);
    }

    public static SubmitResult duplicated(String key, PaymentStatus status) {
        return new SubmitResult(key, status, true);
    }
}
