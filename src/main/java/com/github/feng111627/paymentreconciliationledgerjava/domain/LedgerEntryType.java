package com.github.feng111627.paymentreconciliationledgerjava.domain;

/**
 * 账本流水类型；所有资金变动都必须以一条流水落账，保证账本守恒可审计。
 *
 * 对账户总额 available + frozen + inflight 的影响：
 * FREEZE / SETTLE / UNFREEZE / REVERSE：总额不变（仅口径间移动）；
 * FEE：冻结 -> 账户外（总额减少，手续费支出口径）；
 * CONFIRM：在途 -> 账户外（总额减少，支付完成出账）。
 */
public enum LedgerEntryType {
    FREEZE,
    SETTLE,
    FEE,
    UNFREEZE,
    REVERSE,
    CONFIRM
}
