package com.github.feng111627.paymentreconciliationledgerjava.domain;

import java.math.BigDecimal;
import java.time.Instant;

/** 账本流水：每一笔资金变动都留痕，类型决定对 available/frozen/inflight 的影响方向。 */
public class LedgerEntry {

    private final String id;
    private final String accountId;
    private final String paymentId;
    private final LedgerEntryType type;
    private final BigDecimal amount;
    private final CurrencyCode currency;
    private final Instant createdAt;
    private final String memo;

    public LedgerEntry(String id, String accountId, String paymentId, LedgerEntryType type,
                       BigDecimal amount, CurrencyCode currency, Instant createdAt, String memo) {
        this.id = id;
        this.accountId = accountId;
        this.paymentId = paymentId;
        this.type = type;
        this.amount = amount;
        this.currency = currency;
        this.createdAt = createdAt;
        this.memo = memo;
    }

    public String getId() { return id; }
    public String getAccountId() { return accountId; }
    public String getPaymentId() { return paymentId; }
    public LedgerEntryType getType() { return type; }
    public BigDecimal getAmount() { return amount; }
    public CurrencyCode getCurrency() { return currency; }
    public Instant getCreatedAt() { return createdAt; }
    public String getMemo() { return memo; }
}
