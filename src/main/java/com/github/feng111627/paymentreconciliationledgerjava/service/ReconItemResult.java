package com.github.feng111627.paymentreconciliationledgerjava.service;

import com.github.feng111627.paymentreconciliationledgerjava.domain.ReconResult;

/** 单条对账结论。 */
public record ReconItemResult(String bizNo, ReconResult result, String detail, boolean replayed) {}
