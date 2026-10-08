package com.github.feng111627.paymentreconciliationledgerjava.dto;

import java.math.BigDecimal;

/** 支付受理请求；currency 用 String 接收，以便区分“币种不支持/不符”。 */
public record AcceptRequest(String bizKey, String accountId,
                            BigDecimal amount, String currency, BigDecimal fee) {}
