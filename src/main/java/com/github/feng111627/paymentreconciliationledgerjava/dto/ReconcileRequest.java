package com.github.feng111627.paymentreconciliationledgerjava.dto;

import java.util.List;

/** 对账请求；autoReverse=true 时对金额不符等差异按规则触发冲正。 */
public record ReconcileRequest(String batchId, List<EntryDto> entries, boolean autoReverse) {}
