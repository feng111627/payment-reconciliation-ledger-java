package com.github.feng111627.paymentreconciliationledgerjava.domain;

/** 单条对账条目的结论，结论一旦产生不可被重复对账覆盖。 */
public enum EntryConclusion {
    MATCHED,
    LOCAL_MISSING,
    STATEMENT_MISSING,
    AMOUNT_MISMATCH,
    CURRENCY_MISMATCH,
    MALFORMED_ENTRY
}
