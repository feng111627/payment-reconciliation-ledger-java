package com.github.feng111627.paymentreconciliationledgerjava.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** 账本流水：任何余额/冻结/在途/手续费变动都必须有一条流水。 */
public class LedgerEntry {
    private final String entryId;
    private final String accountId;
    private final LedgerEntryType type;
    private final BigDecimal amount;
    private final String refId;
    private final Instant createdAt;

    public LedgerEntry(String accountId, LedgerEntryType type, BigDecimal amount, String refId) {
        this.entryId = UUID.randomUUID().toString();
        this.accountId = accountId;
        this.type = type;
        this.amount = amount;
        this.refId = refId;
        this.createdAt = Instant.now();
    }

    public String getEntryId() { return entryId; }
    public String getAccountId() { return accountId; }
    public LedgerEntryType getType() { return type; }
    public BigDecimal getAmount() { return amount; }
    public String getRefId() { return refId; }
    public Instant getCreatedAt() { return createdAt; }
}
