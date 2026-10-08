package com.github.feng111627.paymentreconciliationledgerjava.domain;

import java.time.Instant;

/** 对账结论记录：按 batchId + bizKey 唯一，结论产生后不可改写。 */
public class ReconciliationRecord {

    private final String batchId;
    private final String bizKey;
    private final EntryConclusion conclusion;
    private final String detail;
    private final Instant createdAt;

    public ReconciliationRecord(String batchId, String bizKey, EntryConclusion conclusion,
                                String detail, Instant createdAt) {
        this.batchId = batchId;
        this.bizKey = bizKey;
        this.conclusion = conclusion;
        this.detail = detail;
        this.createdAt = createdAt;
    }

    public String getBatchId() { return batchId; }
    public String getBizKey() { return bizKey; }
    public EntryConclusion getConclusion() { return conclusion; }
    public String getDetail() { return detail; }
    public Instant getCreatedAt() { return createdAt; }
}
