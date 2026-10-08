package com.github.feng111627.paymentreconciliationledgerjava.error;

import com.github.feng111627.paymentreconciliationledgerjava.domain.PaymentStatus;

/** 非法状态流转；带上 from/to 便于解释。 */
public class IllegalStateTransitionException extends BusinessException {

    private final PaymentStatus from;
    private final PaymentStatus to;

    public IllegalStateTransitionException(String message, PaymentStatus from, PaymentStatus to) {
        super(message);
        this.from = from;
        this.to = to;
    }

    public PaymentStatus getFrom() { return from; }
    public PaymentStatus getTo() { return to; }
}
