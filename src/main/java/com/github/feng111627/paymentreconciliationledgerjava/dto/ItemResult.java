package com.github.feng111627.paymentreconciliationledgerjava.dto;

import com.github.feng111627.paymentreconciliationledgerjava.domain.PaymentStatus;

import java.math.BigDecimal;

/** 单笔结算结果：本次结算金额、累计已结算、结算后状态。 */
public record ItemResult(String bizKey, BigDecimal settledNow,
                         BigDecimal settledTotal, PaymentStatus status) {}
