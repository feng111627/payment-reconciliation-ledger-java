package com.github.feng111627.paymentreconciliationledgerjava.error;

/** 所有业务错误的基类；被拒绝的操作必须显式抛出，绝不静默丢弃。 */
public class BusinessException extends RuntimeException {
    public BusinessException(String message) {
        super(message);
    }

    public BusinessException(String message, Throwable cause) {
        super(message, cause);
    }
}
