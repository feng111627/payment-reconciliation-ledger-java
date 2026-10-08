package com.github.feng111627.paymentreconciliationledgerjava.error;

import java.math.BigDecimal;

/** 可用余额不足；附带上期望与实际金额，便于解释。 */
public class InsufficientBalanceException extends BusinessException {

    private final BigDecimal required;
    private final BigDecimal available;

    public InsufficientBalanceException(String message, BigDecimal required, BigDecimal available) {
        super(message);
        this.required = required;
        this.available = available;
    }

    public BigDecimal getRequired() { return required; }
    public BigDecimal getAvailable() { return available; }
}
