package com.github.feng111627.paymentreconciliationledgerjava.dto;

import java.util.List;

/** 对账结果；replayed=true 表示同一批次重复对账，返回既有结论不重复记账。 */
public record ReconcileResult(String batchId, boolean replayed,
                              List<ReconEntryResult> entries, List<String> actions) {}
