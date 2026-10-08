package com.github.feng111627.paymentreconciliationledgerjava.dto;

import java.util.List;

/** 批量结算结果；replayed=true 表示批次重复提交，返回首次结果不重复记账。 */
public record SettlementResult(String batchId, boolean replayed, List<ItemResult> items) {}
