package com.github.feng111627.paymentreconciliationledgerjava.domain;

/** 支付指令被拒绝时抛出，携带可区分原因。 */
public class PaymentRejectedException extends RuntimeException {
    private final RejectReason reason;

    public PaymentRejectedException(RejectReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RejectReason getReason() {
        return reason;
    }
}
