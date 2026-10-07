package com.github.feng111627.paymentreconciliationledgerjava.domain;

import java.math.BigDecimal;
import java.time.Instant;

/** 支付指令：以幂等键（业务号）唯一。 */
public class PaymentInstruction {
    private final String idempotencyKey;
    private final String accountId;
    private final BigDecimal amount;
    private final String currency;
    private final BigDecimal fee;
    private final Instant createdAt;
    private volatile PaymentStatus status;
    private BigDecimal settledAmount = BigDecimal.ZERO;

    public PaymentInstruction(String idempotencyKey, String accountId, BigDecimal amount,
                              String currency, BigDecimal fee, PaymentStatus status) {
        this.idempotencyKey = idempotencyKey;
        this.accountId = accountId;
        this.amount = amount;
        this.currency = currency;
        this.fee = fee;
        this.status = status;
        this.createdAt = Instant.now();
    }

    public String getIdempotencyKey() { return idempotencyKey; }
    public String getAccountId() { return accountId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public BigDecimal getFee() { return fee; }
    public Instant getCreatedAt() { return createdAt; }
    public PaymentStatus getStatus() { return status; }
    public BigDecimal getSettledAmount() { return settledAmount; }

    public void setStatus(PaymentStatus status) { this.status = status; }
    public void setSettledAmount(BigDecimal settledAmount) { this.settledAmount = settledAmount; }
}
