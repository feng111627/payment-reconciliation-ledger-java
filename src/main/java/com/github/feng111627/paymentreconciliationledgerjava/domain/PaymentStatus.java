package com.github.feng111627.paymentreconciliationledgerjava.domain;

import com.github.feng111627.paymentreconciliationledgerjava.error.IllegalStateTransitionException;

import java.util.EnumSet;
import java.util.Set;

/**
 * 支付指令状态机。所有入口（受理、批量结算、对账触发冲正）共用同一套流转规则。
 *
 * <pre>
 * 受理成功：       -> ACCEPTED（已冻结，含手续费口径）
 * 部分结算：ACCEPTED/PARTIALLY_SETTLED -> PARTIALLY_SETTLED
 * 全额结算：ACCEPTED/PARTIALLY_SETTLED -> SETTLED
 * 对账确认：SETTLED -> CONFIRMED
 * 冲正：    ACCEPTED/PARTIALLY_SETTLED/SETTLED -> REVERSED（终态，按剩余口径回退资金）
 *
 * CONFIRMED 与 REVERSED 为终态，不允许任何后续流转。
 * </pre>
 */
public enum PaymentStatus {
    ACCEPTED,
    PARTIALLY_SETTLED,
    SETTLED,
    CONFIRMED,
    REVERSED;

    private static final Set<PaymentStatus> PARTIAL_SOURCES =
            EnumSet.of(ACCEPTED, PARTIALLY_SETTLED);
    private static final Set<PaymentStatus> FULL_SOURCES =
            EnumSet.of(ACCEPTED, PARTIALLY_SETTLED);
    private static final Set<PaymentStatus> REVERSE_SOURCES =
            EnumSet.of(ACCEPTED, PARTIALLY_SETTLED, SETTLED);

    /** 部分结算后的目标状态。 */
    public PaymentStatus partialSettleTarget() {
        requireIn(PARTIAL_SOURCES, this, PARTIALLY_SETTLED);
        return PARTIALLY_SETTLED;
    }

    /** 全额结算完成后的目标状态。 */
    public PaymentStatus fullSettleTarget() {
        requireIn(FULL_SOURCES, this, SETTLED);
        return SETTLED;
    }

    /** 对账确认。 */
    public PaymentStatus confirmTarget() {
        requireIn(EnumSet.of(SETTLED), this, CONFIRMED);
        return CONFIRMED;
    }

    /** 冲正（允许在结算完成后发现差异时回退；CONFIRMED 后不可再冲正）。 */
    public PaymentStatus reverseTarget() {
        requireIn(REVERSE_SOURCES, this, REVERSED);
        return REVERSED;
    }

    public boolean isTerminal() {
        return this == CONFIRMED || this == REVERSED;
    }

    private static void requireIn(Set<PaymentStatus> allowed, PaymentStatus from, PaymentStatus to) {
        if (!allowed.contains(from)) {
            throw new IllegalStateTransitionException(
                    "非法状态流转: " + from + " -> " + to, from, to);
        }
    }
}
