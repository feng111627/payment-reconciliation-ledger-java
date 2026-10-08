package com.github.feng111627.paymentreconciliationledgerjava.domain;

import java.math.BigDecimal;

/**
 * 账户：可用余额 available、冻结 frozen、在途 inflight。
 * 三个口径只能通过 {@code applyXxx} 方法联动修改，且每次修改都必须对应一条账本流水，
 * 从而保证账本守恒可审计：
 * FREEZE/SETTLE/UNFREEZE/REVERSE 总额不变；FEE/CONFIRM 使总额减少（出账）。
 */
public class Account {

    private final String id;
    private final String ownerName;
    private final CurrencyCode currency;
    private BigDecimal available;
    private BigDecimal frozen;
    private BigDecimal inflight;

    public Account(String id, String ownerName, CurrencyCode currency,
                   BigDecimal available, BigDecimal frozen, BigDecimal inflight) {
        this.id = id;
        this.ownerName = ownerName;
        this.currency = currency;
        this.available = scale(available);
        this.frozen = scale(frozen);
        this.inflight = scale(inflight);
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(Money.SCALE);
    }

    /** 受理冻结：available -> frozen。 */
    public void applyFreeze(BigDecimal amount) {
        this.available = available.subtract(amount);
        this.frozen = frozen.add(amount);
    }

    /** 结算：frozen -> inflight（仍在账户总额内，等待对账确认）。 */
    public void applySettleToInflight(BigDecimal amount) {
        this.frozen = frozen.subtract(amount);
        this.inflight = inflight.add(amount);
    }

    /** 全额结算时扣除手续费：frozen -> 账户外。 */
    public void applyFee(BigDecimal fee) {
        this.frozen = frozen.subtract(fee);
    }

    /** 解除冻结：frozen -> available（未结算即冲正时）。 */
    public void applyUnfreeze(BigDecimal amount) {
        this.frozen = frozen.subtract(amount);
        this.available = available.add(amount);
    }

    /** 已结算部分冲正：inflight -> available。 */
    public void applyReverseInflight(BigDecimal amount) {
        this.inflight = inflight.subtract(amount);
        this.available = available.add(amount);
    }

    /** 对账确认出账：inflight -> 账户外。 */
    public void applyConfirm(BigDecimal amount) {
        this.inflight = inflight.subtract(amount);
    }

    public String getId() { return id; }
    public String getOwnerName() { return ownerName; }
    public CurrencyCode getCurrency() { return currency; }
    public BigDecimal getAvailable() { return available; }
    public BigDecimal getFrozen() { return frozen; }
    public BigDecimal getInflight() { return inflight; }

    /** 账户总额（不含已出账的手续费与已确认支付）。 */
    public BigDecimal getTotal() {
        return available.add(frozen).add(inflight);
    }
}
