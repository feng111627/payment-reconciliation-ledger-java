package com.github.feng111627.paymentreconciliationledgerjava.domain;

/** 支付指令被拒绝的可区分原因。 */
public enum RejectReason {
    DUPLICATE_IDEMPOTENCY_KEY,
    ACCOUNT_NOT_FOUND,
    INSUFFICIENT_BALANCE,
    CURRENCY_MISMATCH,
    INVALID_AMOUNT,
    ILLEGAL_STATE_TRANSITION
}
