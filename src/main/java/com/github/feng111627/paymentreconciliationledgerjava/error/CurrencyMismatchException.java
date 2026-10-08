package com.github.feng111627.paymentreconciliationledgerjava.error;

/** 币种不符（支付币种与账户币种不一致）。 */
public class CurrencyMismatchException extends BusinessException {
    public CurrencyMismatchException(String message) {
        super(message);
    }
}
