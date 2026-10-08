package com.github.feng111627.paymentreconciliationledgerjava.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 幂等记录：以 bizKey 为键，保存首次请求的指纹（金额/币种/账户）与结果。
 * 重复提交命中时返回首次结果，不重复扣款也不多记一笔。
 */
public class IdempotentRecord {

    private final String bizKey;
    private final String requestFingerprint;
    private final boolean accepted;
    private final String paymentId;
    private final RejectReason rejectReason;
    private final Instant createdAt;

    public IdempotentRecord(String bizKey, String requestFingerprint, boolean accepted,
                            String paymentId, RejectReason rejectReason, Instant createdAt) {
        this.bizKey = bizKey;
        this.requestFingerprint = requestFingerprint;
        this.accepted = accepted;
        this.paymentId = paymentId;
        this.rejectReason = rejectReason;
        this.createdAt = createdAt;
    }

    public String getBizKey() { return bizKey; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public boolean isAccepted() { return accepted; }
    public String getPaymentId() { return paymentId; }
    public RejectReason getRejectReason() { return rejectReason; }
    public Instant getCreatedAt() { return createdAt; }
}
