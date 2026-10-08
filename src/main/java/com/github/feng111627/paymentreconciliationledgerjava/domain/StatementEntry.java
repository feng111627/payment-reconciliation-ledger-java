package com.github.feng111627.paymentreconciliationledgerjava.domain;

import java.math.BigDecimal;

/** 对账单中的单条记录；用包装类型允许字段为 null 以识别“字段缺失”。 */
public class StatementEntry {

    private final String bizKey;
    private final BigDecimal amount;
    private final CurrencyCode currency;

    public StatementEntry(String bizKey, BigDecimal amount, CurrencyCode currency) {
        this.bizKey = bizKey;
        this.amount = amount;
        this.currency = currency;
    }

    public String getBizKey() { return bizKey; }
    public BigDecimal getAmount() { return amount; }
    public CurrencyCode getCurrency() { return currency; }
}
