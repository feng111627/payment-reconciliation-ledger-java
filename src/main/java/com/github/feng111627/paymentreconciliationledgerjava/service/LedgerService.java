package com.github.feng111627.paymentreconciliationledgerjava.service;

import com.github.feng111627.paymentreconciliationledgerjava.domain.Account;
import com.github.feng111627.paymentreconciliationledgerjava.domain.LedgerEntry;
import com.github.feng111627.paymentreconciliationledgerjava.domain.LedgerEntryType;
import com.github.feng111627.paymentreconciliationledgerjava.domain.PaymentRejectedException;
import com.github.feng111627.paymentreconciliationledgerjava.domain.RejectReason;
import com.github.feng111627.paymentreconciliationledgerjava.repository.LedgerRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 账本服务：所有账户口径（余额/冻结/在途/手续费）变动都在账户级锁内完成，
 * 且每次变动追加一条流水，保证账本守恒、可审计。
 */
@Service
public class LedgerService {
    private final LedgerRepository ledgerRepository;

    public LedgerService(LedgerRepository ledgerRepository) {
        this.ledgerRepository = ledgerRepository;
    }

    /** 冻结：balance -> frozen。余额不足时拒绝。 */
    public void freeze(Account account, BigDecimal amount, String refId) {
        synchronized (account) {
            if (account.getBalance().compareTo(amount) < 0) {
                throw new PaymentRejectedException(RejectReason.INSUFFICIENT_BALANCE,
                        "balance " + account.getBalance() + " < " + amount);
            }
            account.setBalance(account.getBalance().subtract(amount));
            account.setFrozen(account.getFrozen().add(amount));
            append(account, LedgerEntryType.FREEZE, amount, refId);
        }
    }

    /** 解冻：frozen -> balance（冲正/回滚路径，不重复扣款）。 */
    public void unfreeze(Account account, BigDecimal amount, String refId) {
        synchronized (account) {
            requireFrozen(account, amount);
            account.setFrozen(account.getFrozen().subtract(amount));
            account.setBalance(account.getBalance().add(amount));
            append(account, LedgerEntryType.UNFREEZE, amount, refId);
        }
    }

    /** 冻结转在途：frozen -> inTransit（结算中间态）。 */
    public void moveToInTransit(Account account, BigDecimal amount, String refId) {
        synchronized (account) {
            requireFrozen(account, amount);
            account.setFrozen(account.getFrozen().subtract(amount));
            account.setInTransit(account.getInTransit().add(amount));
            append(account, LedgerEntryType.FREEZE, amount.negate(), refId);
        }
    }

    /** 在途结算出账：inTransit 减少，资金离账。 */
    public void settleInTransit(Account account, BigDecimal amount, String refId) {
        synchronized (account) {
            if (account.getInTransit().compareTo(amount) < 0) {
                throw new IllegalStateException("inTransit " + account.getInTransit() + " < " + amount);
            }
            account.setInTransit(account.getInTransit().subtract(amount));
            append(account, LedgerEntryType.DEBIT, amount, refId);
        }
    }

    /** 收取手续费：balance -> feeTotal。 */
    public void chargeFee(Account account, BigDecimal fee, String refId) {
        synchronized (account) {
            if (fee.signum() <= 0) {
                return;
            }
            if (account.getBalance().compareTo(fee) < 0) {
                throw new PaymentRejectedException(RejectReason.INSUFFICIENT_BALANCE,
                        "balance " + account.getBalance() + " < fee " + fee);
            }
            account.setBalance(account.getBalance().subtract(fee));
            account.setFeeTotal(account.getFeeTotal().add(fee));
            append(account, LedgerEntryType.FEE, fee, refId);
        }
    }

    /** 冲正入账：资金退回 balance。 */
    public void reversalCredit(Account account, BigDecimal amount, String refId) {
        synchronized (account) {
            account.setBalance(account.getBalance().add(amount));
            append(account, LedgerEntryType.REVERSAL_CREDIT, amount, refId);
        }
    }

    /** 冲正退手续费：feeTotal -> balance。 */
    public void refundFee(Account account, BigDecimal fee, String refId) {
        synchronized (account) {
            if (fee.signum() <= 0) {
                return;
            }
            account.setFeeTotal(account.getFeeTotal().subtract(fee));
            account.setBalance(account.getBalance().add(fee));
            append(account, LedgerEntryType.FEE, fee.negate(), refId);
        }
    }

    private void requireFrozen(Account account, BigDecimal amount) {
        if (account.getFrozen().compareTo(amount) < 0) {
            throw new IllegalStateException("frozen " + account.getFrozen() + " < " + amount);
        }
    }

    private void append(Account account, LedgerEntryType type, BigDecimal amount, String refId) {
        ledgerRepository.append(new LedgerEntry(account.getAccountId(), type, amount, refId));
    }
}
