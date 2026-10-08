package com.github.feng111627.paymentreconciliationledgerjava.domain;

import com.github.feng111627.paymentreconciliationledgerjava.error.IllegalStateTransitionException;
import com.github.feng111627.paymentreconciliationledgerjava.error.PaymentValidationException;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 支付指令：幂等键 bizKey 唯一；金额、手续费、状态与累计结算金额。
 * 状态流转统一委托 {@link PaymentStatus}，任何非法流转都会抛
 * {@link IllegalStateTransitionException}。
 */
public class Payment {

    private final String id;
    private final String bizKey;
    private final String accountId;
    private final CurrencyCode currency;
    private final BigDecimal amount;
    private final BigDecimal fee;
    private PaymentStatus status;
    private BigDecimal settledAmount;
    private final Instant createdAt;
    private Instant confirmedAt;
    private Instant reversedAt;

    public Payment(String id, String bizKey, String accountId, CurrencyCode currency,
                   BigDecimal amount, BigDecimal fee, PaymentStatus status,
                   BigDecimal settledAmount, Instant createdAt,
                   Instant confirmedAt, Instant reversedAt) {
        this.id = id;
        this.bizKey = bizKey;
        this.accountId = accountId;
        this.currency = currency;
        this.amount = amount.setScale(Money.SCALE);
        this.fee = fee.setScale(Money.SCALE);
        this.status = status;
        this.settledAmount = settledAmount.setScale(Money.SCALE);
        this.createdAt = createdAt;
        this.confirmedAt = confirmedAt;
        this.reversedAt = reversedAt;
    }

    /**
     * 登记一次部分结算。要求 part 为正数且累计不超过指令金额；
     * 目标状态由本次结算后是否结清决定。
     */
    public PaymentStatus registerSettlement(BigDecimal part, Instant now) {
        BigDecimal next = settledAmount.add(part);
        if (part.signum() <= 0 || next.compareTo(amount) > 0) {
            throw new PaymentValidationException(
                    "结算金额非法：本次=" + part + "，累计=" + next + "，指令金额=" + amount, null);
        }
        boolean full = next.compareTo(amount) == 0;
        this.status = full ? status.fullSettleTarget() : status.partialSettleTarget();
        this.settledAmount = next;
        return this.status;
    }

    public PaymentStatus confirm(Instant now) {
        this.status = status.confirmTarget();
        this.confirmedAt = now;
        return this.status;
    }

    public PaymentStatus reverse(Instant now) {
        this.status = status.reverseTarget();
        this.reversedAt = now;
        return this.status;
    }

    /** 尚未结算的金额（冲正时需要从冻结退回可用的部分）。 */
    public BigDecimal getUnsettledAmount() {
        return amount.subtract(settledAmount);
    }

    public String getId() { return id; }
    public String getBizKey() { return bizKey; }
    public String getAccountId() { return accountId; }
    public CurrencyCode getCurrency() { return currency; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getFee() { return fee; }
    public PaymentStatus getStatus() { return status; }
    public BigDecimal getSettledAmount() { return settledAmount; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public Instant getReversedAt() { return reversedAt; }
}
