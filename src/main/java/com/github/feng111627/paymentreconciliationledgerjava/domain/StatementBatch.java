package com.github.feng111627.paymentreconciliationledgerjava.domain;

import java.time.Instant;
import java.util.List;

/** 外部对账单（一批条目；条目可能乱序、重复或字段缺失）。 */
public class StatementBatch {

    private final String batchId;
    private final List<StatementEntry> entries;
    private final Instant receivedAt;
    private boolean reconciled;
    private Instant reconciledAt;

    public StatementBatch(String batchId, List<StatementEntry> entries, Instant receivedAt,
                          boolean reconciled, Instant reconciledAt) {
        this.batchId = batchId;
        this.entries = entries;
        this.receivedAt = receivedAt;
        this.reconciled = reconciled;
        this.reconciledAt = reconciledAt;
    }

    public void markReconciled(Instant at) {
        this.reconciled = true;
        this.reconciledAt = at;
    }

    public String getBatchId() { return batchId; }
    public List<StatementEntry> getEntries() { return entries; }
    public Instant getReceivedAt() { return receivedAt; }
    public boolean isReconciled() { return reconciled; }
    public Instant getReconciledAt() { return reconciledAt; }
}
