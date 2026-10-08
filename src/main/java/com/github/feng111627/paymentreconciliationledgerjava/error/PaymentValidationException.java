package com.github.feng111627.paymentreconciliationledgerjava.error;

import com.github.feng111627.paymentreconciliationledgerjava.domain.RejectReason;

/** 受理校验失败；携带可区分的拒绝原因。 */
public class PaymentValidationException extends BusinessException {

    private final RejectReason reason;

    public PaymentValidationException(String message, RejectReason reason) {
        super(message);
        this.reason = reason;
    }

    public RejectReason getReason() {
        return reason;
    }
}
