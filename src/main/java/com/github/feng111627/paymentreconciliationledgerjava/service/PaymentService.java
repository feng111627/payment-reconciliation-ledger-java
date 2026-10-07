package com.github.feng111627.paymentreconciliationledgerjava.service;

import com.github.feng111627.paymentreconciliationledgerjava.domain.Account;
import com.github.feng111627.paymentreconciliationledgerjava.domain.IllegalTransitionException;
import com.github.feng111627.paymentreconciliationledgerjava.domain.PaymentInstruction;
import com.github.feng111627.paymentreconciliationledgerjava.domain.PaymentRejectedException;
import com.github.feng111627.paymentreconciliationledgerjava.domain.PaymentStatus;
import com.github.feng111627.paymentreconciliationledgerjava.domain.RejectReason;
import com.github.feng111627.paymentreconciliationledgerjava.repository.AccountRepository;
import com.github.feng111627.paymentreconciliationledgerjava.repository.PaymentRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 支付指令受理 / 批量结算 / 冲正。
 *
 * 幂等规则：以幂等键（业务号）为唯一约束，同一键只生效一次；
 * 重复提交（含并发）返回首次受理结果，不重复冻结、不重复记账。
 *
 * 状态机：
 *   (none) -> RECEIVED            受理并冻结
 *   RECEIVED -> PARTIALLY_SETTLED 部分结算
 *   RECEIVED -> SETTLED           全额结算
 *   PARTIALLY_SETTLED -> SETTLED  补足结算
 *   RECEIVED/PARTIALLY_SETTLED/SETTLED -> REVERSED 冲正
 * 其余流转一律抛出 IllegalTransitionException。
 */
@Service
public class PaymentService {
    /** 手续费率：千分之一，两位小数。 */
    static final BigDecimal FEE_RATE = new BigDecimal("0.001");

    private final PaymentRepository paymentRepository;
    private final AccountRepository accountRepository;
    private final LedgerService ledgerService;
    /** 每个幂等键一把锁，保证并发重复提交只生效一次。 */
    private final ConcurrentHashMap<String, Object> keyLocks = new ConcurrentHashMap<>();

    public PaymentService(PaymentRepository paymentRepository,
                          AccountRepository accountRepository,
                          LedgerService ledgerService) {
        this.paymentRepository = paymentRepository;
        this.accountRepository = accountRepository;
        this.ledgerService = ledgerService;
    }

    public SubmitResult submit(String idempotencyKey, String accountId, BigDecimal amount, String currency) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new PaymentRejectedException(RejectReason.INVALID_AMOUNT, "idempotencyKey required");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new PaymentRejectedException(RejectReason.INVALID_AMOUNT, "amount must be positive: " + amount);
        }
        synchronized (lockFor(idempotencyKey)) {
            var existing = paymentRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                PaymentInstruction p = existing.get();
                return SubmitResult.duplicated(p.getIdempotencyKey(), p.getStatus());
            }
            Account account = accountRepository.findById(accountId)
                    .orElseThrow(() -> new PaymentRejectedException(RejectReason.ACCOUNT_NOT_FOUND, accountId));
            if (!account.getCurrency().equals(currency)) {
                throw new PaymentRejectedException(RejectReason.CURRENCY_MISMATCH,
                        "account currency " + account.getCurrency() + " != " + currency);
            }
            BigDecimal fee = feeOf(amount);
            PaymentInstruction instruction = new PaymentInstruction(
                    idempotencyKey, accountId, amount, currency, fee, PaymentStatus.RECEIVED);
            // 先落库占位再冻结：同一键在锁内只会走到这里一次
            paymentRepository.putIfAbsent(instruction);
            ledgerService.freeze(account, amount, idempotencyKey);
            return SubmitResult.accepted(idempotencyKey, PaymentStatus.RECEIVED);
        }
    }

    /** 批量全额结算。键排序后逐个结算，结果顺序与去重后的键一致。 */
    public List<PaymentInstruction> settleBatch(List<String> idempotencyKeys) {
        List<String> keys = idempotencyKeys.stream().distinct().sorted().toList();
        List<PaymentInstruction> result = new ArrayList<>();
        for (String key : keys) {
            result.add(settle(key, null));
        }
        return result;
    }

    /**
     * 结算一笔：settleAmount 为空表示全额结算剩余部分；否则为部分结算。
     * 资金路径：frozen -> inTransit -> 出账；全额结清时收取手续费。
     */
    public PaymentInstruction settle(String idempotencyKey, BigDecimal settleAmount) {
        synchronized (lockFor(idempotencyKey)) {
            PaymentInstruction p = paymentRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new PaymentRejectedException(RejectReason.ACCOUNT_NOT_FOUND,
                            "payment not found: " + idempotencyKey));
            if (p.getStatus() != PaymentStatus.RECEIVED && p.getStatus() != PaymentStatus.PARTIALLY_SETTLED) {
                throw new IllegalTransitionException(p.getStatus(), PaymentStatus.SETTLED);
            }
            Account account = accountRepository.findById(p.getAccountId()).orElseThrow();
            BigDecimal remaining = p.getAmount().subtract(p.getSettledAmount());
            BigDecimal amount = settleAmount == null ? remaining : settleAmount;
            if (amount.signum() <= 0 || amount.compareTo(remaining) > 0) {
                throw new PaymentRejectedException(RejectReason.INVALID_AMOUNT,
                        "settle amount " + amount + " invalid, remaining " + remaining);
            }
            ledgerService.moveToInTransit(account, amount, idempotencyKey);
            ledgerService.settleInTransit(account, amount, idempotencyKey);
            BigDecimal settled = p.getSettledAmount().add(amount);
            p.setSettledAmount(settled);
            if (settled.compareTo(p.getAmount()) == 0) {
                ledgerService.chargeFee(account, p.getFee(), idempotencyKey);
                p.setStatus(PaymentStatus.SETTLED);
            } else {
                p.setStatus(PaymentStatus.PARTIALLY_SETTLED);
            }
            return p;
        }
    }

    /**
     * 冲正：未结算部分解冻退回；已结算部分冲正入账并退手续费。只生效一次。
     */
    public PaymentInstruction reverse(String idempotencyKey, String reason) {
        synchronized (lockFor(idempotencyKey)) {
            PaymentInstruction p = paymentRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new PaymentRejectedException(RejectReason.ACCOUNT_NOT_FOUND,
                            "payment not found: " + idempotencyKey));
            switch (p.getStatus()) {
                case REVERSED, REJECTED ->
                        throw new IllegalTransitionException(p.getStatus(), PaymentStatus.REVERSED);
                default -> { /* RECEIVED / PARTIALLY_SETTLED / SETTLED 允许冲正 */ }
            }
            Account account = accountRepository.findById(p.getAccountId()).orElseThrow();
            BigDecimal unsettled = p.getAmount().subtract(p.getSettledAmount());
            if (unsettled.signum() > 0) {
                ledgerService.unfreeze(account, unsettled, idempotencyKey);
            }
            if (p.getSettledAmount().signum() > 0) {
                ledgerService.reversalCredit(account, p.getSettledAmount(), idempotencyKey);
            }
            if (p.getStatus() == PaymentStatus.SETTLED) {
                ledgerService.refundFee(account, p.getFee(), idempotencyKey);
            }
            p.setStatus(PaymentStatus.REVERSED);
            return p;
        }
    }

    static BigDecimal feeOf(BigDecimal amount) {
        return amount.multiply(FEE_RATE).setScale(2, RoundingMode.HALF_UP);
    }

    private Object lockFor(String key) {
        return keyLocks.computeIfAbsent(key, k -> new Object());
    }
}
