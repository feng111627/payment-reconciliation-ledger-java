package com.github.feng111627.paymentreconciliationledgerjava.error;

/** 账户不存在。 */
public class AccountNotFoundException extends BusinessException {
    public AccountNotFoundException(String message) {
        super(message);
    }
}
