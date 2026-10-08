package com.github.feng111627.paymentreconciliationledgerjava.dto;

import com.github.feng111627.paymentreconciliationledgerjava.domain.RejectReason;

/** 受理结果；replayed=true 表示命中幂等（返回首次结果，未再次扣款）。 */
public record AcceptResult(String bizKey, String paymentId, boolean accepted,
                           boolean replayed, RejectReason rejectReason, String message) {}
