package com.github.feng111627.paymentreconciliationledgerjava.domain;

/** 单条对账结论，可区分且可复现。 */
public enum ReconResult {
    MATCHED,
    AMOUNT_MISMATCH,
    CURRENCY_MISMATCH,
    MISSING_REQUIRED_FIELDS,
    PAYMENT_NOT_FOUND,
    DUPLICATE_RECONCILED
}
