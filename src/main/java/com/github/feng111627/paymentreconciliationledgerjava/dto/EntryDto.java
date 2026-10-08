package com.github.feng111627.paymentreconciliationledgerjava.dto;

import java.math.BigDecimal;

/** 对账单条目 DTO；字段允许为 null，用于识别“字段缺失”。 */
public record EntryDto(String bizKey, BigDecimal amount, String currency) {}
