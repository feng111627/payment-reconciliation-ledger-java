package com.github.feng111627.paymentreconciliationledgerjava.service;

import com.github.feng111627.paymentreconciliationledgerjava.domain.*;
import com.github.feng111627.paymentreconciliationledgerjava.error.AccountNotFoundException;
import com.github.feng111627.paymentreconciliationledgerjava.error.CurrencyMismatchException;
import com.github.feng111627.paymentreconciliationledgerjava.error.InsufficientBalanceException;
import com.github.feng111627.paymentreconciliationledgerjava.store.AccountRepository;
import com.github.feng111627.paymentreconciliationledgerjava.store.LedgerRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 账本服务：所有资金口径变动的唯一入口。
 * 1) 每次变动在“账户锁”内完成：校验 -> 修改账户口径 -> 追加一条流水，保证并发安全；
 * 2) 金额币种必须与账户币种一致，否则拒绝；
 * 3) 流水只追加不修改，账户口径变化与流水严格一一对应（账本守恒）。
 */
@Service
public class LedgerService {

    private final AccountRepository accountRepository;
    private final LedgerRepository ledgerRepository;

    /** 每个账户一把锁，序列化同一账户上的并发资金操作。 */
    private final ConcurrentMap<String, Object> accountLocks = new ConcurrentHashMap<>();

    public LedgerService(AccountRepository accountRepository, LedgerRepository ledgerRepository) {
        this.accountRepository = accountRepository;
        this.ledgerRepository = ledgerRepository;
    }

    private Object lockOf(String accountId) {
        return accountLocks.computeIfAbsent(accountId, k -> new Object());
    }

    private Account requireAccount(String accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException("账户不存在: " + accountId));
    }

    private static void requirePositive(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("金额必须为正数: " + amount);
        }
    }

    private void requireCurrency(Account account, CurrencyCode currency) {
        if (account.getCurrency() != currency) {
            throw new CurrencyMismatchException("账户币种 " + account.getCurrency()
                    + " 与操作币种 " + currency + " 不一致，账户=" + account.getId());
        }
    }

    private void post(String accountId, String paymentId, LedgerEntryType type,
                      BigDecimal amount, CurrencyCode currency, String memo,
                      java.util.function.Consumer<Account> mutation) {
        requirePositive(amount);
        synchronized (lockOf(accountId)) {
            Account account = requireAccount(accountId);
            requireCurrency(account, currency);
            mutation.accept(account);
            accountRepository.save(account);
            ledgerRepository.append(new LedgerEntry(
                    ledgerRepository.nextId(), accountId, paymentId, type,
                    amount.setScale(Money.SCALE), currency, Instant.now(), memo));
        }
    }

    /** 受理冻结：校验可用余额足够后，available -> frozen。 */
    public void freeze(String accountId, String paymentId, BigDecimal amount, String memo) {
        CurrencyCode currency = requireAccount(accountId).getCurrency();
        requirePositive(amount);
        synchronized (lockOf(accountId)) {
            Account account = requireAccount(accountId);
            if (account.getAvailable().compareTo(amount) < 0) {
                throw new InsufficientBalanceException(
                        "可用余额不足：需要冻结 " + amount + "，可用 " + account.getAvailable()
                                + "，账户=" + accountId,
                        amount, account.getAvailable());
            }
            account.applyFreeze(amount);
            accountRepository.save(account);
            ledgerRepository.append(new LedgerEntry(
                    ledgerRepository.nextId(), accountId, paymentId, LedgerEntryType.FREEZE,
                    amount.setScale(Money.SCALE), currency, Instant.now(), memo));
        }
    }

    /** 结算：frozen -> inflight（部分结算可多次调用）。 */
    public void settle(String accountId, String paymentId, BigDecimal amount, String memo) {
        CurrencyCode currency = requireAccount(accountId).getCurrency();
        post(accountId, paymentId, LedgerEntryType.SETTLE, amount, currency, memo,
                account -> {
                    if (account.getFrozen().compareTo(amount) < 0) {
                        throw new InsufficientBalanceException(
                                "冻结余额不足：需结算 " + amount + "，冻结 " + account.getFrozen(),
                                amount, account.getFrozen());
                    }
                    account.applySettleToInflight(amount);
                });
    }

    /** 全额结算时扣除手续费：frozen -> 账户外（FEE 出账，不进入在途）。 */
    public void fee(String accountId, String paymentId, BigDecimal amount, String memo) {
        CurrencyCode currency = requireAccount(accountId).getCurrency();
        if (amount.signum() == 0) {
            return;
        }
        post(accountId, paymentId, LedgerEntryType.FEE, amount, currency, memo,
                account -> {
                    if (account.getFrozen().compareTo(amount) < 0) {
                        throw new InsufficientBalanceException(
                                "冻结余额不足以支付手续费 " + amount + "，冻结 " + account.getFrozen(),
                                amount, account.getFrozen());
                    }
                    account.applyFee(amount);
                });
    }

    /** 解除冻结：frozen -> available（未结算部分冲正）。 */
    public void unfreeze(String accountId, String paymentId, BigDecimal amount, String memo) {
        if (amount.signum() == 0) {
            return;
        }
        CurrencyCode currency = requireAccount(accountId).getCurrency();
        post(accountId, paymentId, LedgerEntryType.UNFREEZE, amount, currency, memo,
                account -> {
                    if (account.getFrozen().compareTo(amount) < 0) {
                        throw new InsufficientBalanceException(
                                "冻结余额不足：需解冻 " + amount + "，冻结 " + account.getFrozen(),
                                amount, account.getFrozen());
                    }
                    account.applyUnfreeze(amount);
                });
    }

    /** 已结算部分冲正：inflight -> available。 */
    public void reverse(String accountId, String paymentId, BigDecimal amount, String memo) {
        if (amount.signum() == 0) {
            return;
        }
        CurrencyCode currency = requireAccount(accountId).getCurrency();
        post(accountId, paymentId, LedgerEntryType.REVERSE, amount, currency, memo,
                account -> {
                    if (account.getInflight().compareTo(amount) < 0) {
                        throw new InsufficientBalanceException(
                                "在途余额不足：需冲正 " + amount + "，在途 " + account.getInflight(),
                                amount, account.getInflight());
                    }
                    account.applyReverseInflight(amount);
                });
    }

    /** 对账确认出账：inflight -> 账户外。 */
    public void confirm(String accountId, String paymentId, BigDecimal amount, String memo) {
        CurrencyCode currency = requireAccount(accountId).getCurrency();
        post(accountId, paymentId, LedgerEntryType.CONFIRM, amount, currency, memo,
                account -> {
                    if (account.getInflight().compareTo(amount) < 0) {
                        throw new InsufficientBalanceException(
                                "在途余额不足：需确认出账 " + amount + "，在途 " + account.getInflight(),
                                amount, account.getInflight());
                    }
                    account.applyConfirm(amount);
                });
    }

    public List<LedgerEntry> entriesOfPayment(String paymentId) {
        return ledgerRepository.findByPaymentId(paymentId);
    }

    public List<LedgerEntry> allEntries() {
        return ledgerRepository.findAll();
    }

    public AccountRepository accountRepository() {
        return accountRepository;
    }
}
