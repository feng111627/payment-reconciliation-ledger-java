package com.github.feng111627.paymentreconciliationledgerjava.domain;

/**
 * 支付指令状态机。
 * RECEIVED -> SETTLED / PARTIALLY_SETTLED / REVERSED
 * PARTIALLY_SETTLED -> SETTLED / REVERSED
 * SETTLED -> REVERSED (对账触发冲正)
 */
public enum PaymentStatus {
    RECEIVED,
    SETTLED,
    PARTIALLY_SETTLED,
    REVERSED,
    REJECTED
}
