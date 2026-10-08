package com.github.feng111627.paymentreconciliationledgerjava.dto;

import com.github.feng111627.paymentreconciliationledgerjava.domain.EntryConclusion;

/** 单条对账结论。 */
public record ReconEntryResult(String bizKey, EntryConclusion conclusion, String detail) {}
