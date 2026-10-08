package com.github.feng111627.paymentreconciliationledgerjava.domain;

/** 支付受理被拒绝的可区分原因（绝不静默丢弃、绝不伪装成功）。 */
public enum RejectReason {
    INVALID_AMOUNT,
    CURRENCY_NOT_SUPPORTED,
    ACCOUNT_NOT_FOUND,
    CURRENCY_MISMATCH,
    INSUFFICIENT_BALANCE
}
