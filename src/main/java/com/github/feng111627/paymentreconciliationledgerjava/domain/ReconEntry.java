package com.github.feng111627.paymentreconciliationledgerjava.domain;

import java.math.BigDecimal;

/** 对账单条目（来自本地对账单文件/请求）。字段可缺失以模拟畸形数据。 */
public class ReconEntry {
    private String bizNo;
    private BigDecimal amount;
    private String currency;

    public ReconEntry() {}

    public ReconEntry(String bizNo, BigDecimal amount, String currency) {
        this.bizNo = bizNo;
        this.amount = amount;
        this.currency = currency;
    }

    public String getBizNo() { return bizNo; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }

    public void setBizNo(String bizNo) { this.bizNo = bizNo; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public void setCurrency(String currency) { this.currency = currency; }
}
