package com.github.feng111627.paymentreconciliationledgerjava.domain;

/** 非法状态流转。 */
public class IllegalTransitionException extends RuntimeException {
    private final PaymentStatus from;
    private final PaymentStatus to;

    public IllegalTransitionException(PaymentStatus from, PaymentStatus to) {
        super("Illegal state transition: " + from + " -> " + to);
        this.from = from;
        this.to = to;
    }

    public PaymentStatus getFrom() { return from; }
    public PaymentStatus getTo() { return to; }
}
