package com.github.feng111627.paymentreconciliationledgerjava.domain;

import java.time.Instant;

/** 对账结论记录：同一对账单同一条目重复对账返回已存结论，不重复记账。 */
public class ReconRecord {
    private final String statementId;
    private final String bizNo;
    private final ReconResult result;
    private final String detail;
    private final Instant createdAt;

    public ReconRecord(String statementId, String bizNo, ReconResult result, String detail) {
        this.statementId = statementId;
        this.bizNo = bizNo;
        this.result = result;
        this.detail = detail;
        this.createdAt = Instant.now();
    }

    public String getStatementId() { return statementId; }
    public String getBizNo() { return bizNo; }
    public ReconResult getResult() { return result; }
    public String getDetail() { return detail; }
    public Instant getCreatedAt() { return createdAt; }
}
