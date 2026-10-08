package com.github.feng111627.paymentreconciliationledgerjava.domain;

import com.github.feng111627.paymentreconciliationledgerjava.error.CurrencyMismatchException;
import com.github.feng111627.paymentreconciliationledgerjava.error.PaymentValidationException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 金额值对象：金额 + 币种。
 * 规则：
 * 1) 金额非 null，且必须为正数（金额非法在入口被拒绝，不静默处理）；
 * 2) 金额统一两位小数（HALF_UP）；
 * 3) 不同币种不可比较、不可运算，直接抛 CurrencyMismatchException。
 */
public final class Money {

    public static final int SCALE = 2;

    private final BigDecimal amount;
    private final CurrencyCode currency;

    private Money(BigDecimal amount, CurrencyCode currency) {
        this.amount = amount.setScale(SCALE, RoundingMode.HALF_UP);
        this.currency = currency;
    }

    /** 构造金额；amount/currency 非法时给出可区分原因。 */
    public static Money of(BigDecimal amount, CurrencyCode currency) {
        if (currency == null) {
            throw new PaymentValidationException("币种不能为空", null);
        }
        if (amount == null) {
            throw new PaymentValidationException("金额不能为空", null);
        }
        if (amount.signum() <= 0) {
            throw new PaymentValidationException("金额必须为正数: " + amount, null);
        }
        return new Money(amount, currency);
    }

    /** 零元（手续费允许为零的场景使用）。 */
    public static Money zero(CurrencyCode currency) {
        if (currency == null) {
            throw new PaymentValidationException("币种不能为空", null);
        }
        return new Money(BigDecimal.ZERO, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(this.amount.add(other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(this.amount.subtract(other.amount), currency);
    }

    public boolean isGreaterThan(Money other) {
        requireSameCurrency(other);
        return this.amount.compareTo(other.amount) > 0;
    }

    public boolean isLessThan(Money other) {
        requireSameCurrency(other);
        return this.amount.compareTo(other.amount) < 0;
    }

    private void requireSameCurrency(Money other) {
        if (other == null || other.currency != this.currency) {
            throw new CurrencyMismatchException(
                    "币种不一致，无法运算: " + currency + " vs " + (other == null ? "null" : other.currency));
        }
    }

    public BigDecimal amount() {
        return amount;
    }

    public CurrencyCode currency() {
        return currency;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Money money)) {
            return false;
        }
        return amount.compareTo(money.amount) == 0 && currency == money.currency;
    }

    @Override
    public int hashCode() {
        return Objects.hash(amount.stripTrailingZeros(), currency);
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency;
    }
}
