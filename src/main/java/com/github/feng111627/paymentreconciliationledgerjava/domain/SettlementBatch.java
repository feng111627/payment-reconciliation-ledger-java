package com.github.feng111627.paymentreconciliationledgerjava.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 批量结算请求：batchId 幂等；items 为业务号 + 本次结算金额（允许部分结算）。
 */
public class SettlementBatch {

    private final String batchId;
    private final List<Item> items;
    private final Instant createdAt;

    public SettlementBatch(String batchId, List<Item> items, Instant createdAt) {
        this.batchId = batchId;
        this.items = items;
        this.createdAt = createdAt;
    }

    public String getBatchId() { return batchId; }
    public List<Item> getItems() { return items; }
    public Instant getCreatedAt() { return createdAt; }

    /** 单条结算项：业务号 + 本次结算金额。 */
    public record Item(String bizKey, BigDecimal amount) {}
}
