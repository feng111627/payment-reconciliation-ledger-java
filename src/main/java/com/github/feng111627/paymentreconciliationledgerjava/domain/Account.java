package com.github.feng111627.paymentreconciliationledgerjava.domain;

import java.math.BigDecimal;

/**
 * 账户：本地存储模拟。
 * 守恒口径：balance + frozen + inTransit 变动必须全部有账本流水对应。
 */
public class Account {
    private final String accountId;
    private final String currency;
    private BigDecimal balance = BigDecimal.ZERO;
    private BigDecimal frozen = BigDecimal.ZERO;
    private BigDecimal inTransit = BigDecimal.ZERO;
    private BigDecimal feeTotal = BigDecimal.ZERO;

    public Account(String accountId, String currency) {
        this.accountId = accountId;
        this.currency = currency;
    }

    public String getAccountId() { return accountId; }
    public String getCurrency() { return currency; }
    public BigDecimal getBalance() { return balance; }
    public BigDecimal getFrozen() { return frozen; }
    public BigDecimal getInTransit() { return inTransit; }
    public BigDecimal getFeeTotal() { return feeTotal; }

    public void setBalance(BigDecimal balance) { this.balance = balance; }
    public void setFrozen(BigDecimal frozen) { this.frozen = frozen; }
    public void setInTransit(BigDecimal inTransit) { this.inTransit = inTransit; }
    public void setFeeTotal(BigDecimal feeTotal) { this.feeTotal = feeTotal; }
}
