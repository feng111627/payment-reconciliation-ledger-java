package com.github.feng111627.paymentreconciliationledgerjava.domain;

/** 账本流水类型：冻结/解冻/扣款/手续费/冲正。 */
public enum LedgerEntryType {
    FREEZE,
    UNFREEZE,
    DEBIT,
    FEE,
    REVERSAL_CREDIT
}
