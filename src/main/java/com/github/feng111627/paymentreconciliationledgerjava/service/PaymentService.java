package com.github.feng111627.paymentreconciliationledgerjava.service;

import com.github.feng111627.paymentreconciliationledgerjava.domain.*;
import com.github.feng111627.paymentreconciliationledgerjava.dto.AcceptRequest;
import com.github.feng111627.paymentreconciliationledgerjava.dto.AcceptResult;
import com.github.feng111627.paymentreconciliationledgerjava.error.BusinessException;
import com.github.feng111627.paymentreconciliationledgerjava.error.IdempotencyConflictException;
import com.github.feng111627.paymentreconciliationledgerjava.error.InsufficientBalanceException;
import com.github.feng111627.paymentreconciliationledgerjava.error.PaymentValidationException;
import com.github.feng111627.paymentreconciliationledgerjava.store.AccountRepository;
import com.github.feng111627.paymentreconciliationledgerjava.store.IdempotencyRepository;
import com.github.feng111627.paymentreconciliationledgerjava.store.PaymentRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 支付指令受理。
 *
 * 幂等规则：
 * 1) 以 bizKey 为幂等键；同一 bizKey 仅首次请求生效，重复提交（含并发）返回首次结果，
 *    标记 replayed=true，不重复扣款、不多记一笔；
 * 2) 重复提交必须与首次请求指纹一致（账户/金额/币种/手续费），否则抛
 *    {@link IdempotencyConflictException}；
 * 3) 被拒绝的首次请求同样登记幂等结果并在重复提交时原样重放，拒绝原因可区分。
 */
@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final LedgerService ledgerService;
    private final AccountRepository accountRepository;

    private final ConcurrentMap<String, Object> keyLocks = new ConcurrentHashMap<>();
    private final AtomicLong paymentSequence = new AtomicLong();

    public PaymentService(PaymentRepository paymentRepository,
                          IdempotencyRepository idempotencyRepository,
                          LedgerService ledgerService,
                          AccountRepository accountRepository) {
        this.paymentRepository = paymentRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.ledgerService = ledgerService;
        this.accountRepository = accountRepository;
    }

    private Object lockOf(String bizKey) {
        return keyLocks.computeIfAbsent(bizKey, k -> new Object());
    }

    /** 请求指纹：用于识别“同一幂等键但内容不同”的冲突提交。 */
    static String fingerprint(AcceptRequest req) {
        return req.accountId() + "|" + req.amount() + "|" + req.currency() + "|"
                + (req.fee() == null ? BigDecimal.ZERO : req.fee());
    }

    public AcceptResult accept(AcceptRequest request) {
        if (request == null || request.bizKey() == null || request.bizKey().isBlank()) {
            throw new PaymentValidationException("业务号 bizKey 不能为空", null);
        }
        String bizKey = request.bizKey();
        String fp = fingerprint(request);

        // 快速路径：已有首次结果且指纹一致 -> 重放
        var existing = idempotencyRepository.findByBizKey(bizKey);
        if (existing.isPresent()) {
            return replay(existing.get(), fp);
        }

        // 慢速路径：按 bizKey 串行化，二次检查后才是真正的首次执行
        synchronized (lockOf(bizKey)) {
            existing = idempotencyRepository.findByBizKey(bizKey);
            if (existing.isPresent()) {
                return replay(existing.get(), fp);
            }
            return doAccept(request, bizKey, fp);
        }
    }

    private AcceptResult doAccept(AcceptRequest request, String bizKey, String fp) {
        BigDecimal fee = request.fee() == null ? BigDecimal.ZERO : request.fee();

        // ---- 按固定顺序逐项校验，拒绝原因彼此可区分 ----
        if (request.amount() == null || request.amount().signum() <= 0) {
            return rejectAndStore(bizKey, fp, RejectReason.INVALID_AMOUNT,
                    "金额非法：金额必须为正数，实际=" + request.amount());
        }
        CurrencyCode currency;
        if (request.currency() == null || request.currency().isBlank()) {
            return rejectAndStore(bizKey, fp, RejectReason.CURRENCY_NOT_SUPPORTED,
                    "币种缺失");
        }
        try {
            currency = CurrencyCode.valueOf(request.currency().trim());
        } catch (IllegalArgumentException e) {
            return rejectAndStore(bizKey, fp, RejectReason.CURRENCY_NOT_SUPPORTED,
                    "不支持的币种: " + request.currency());
        }
        if (fee.signum() < 0) {
            return rejectAndStore(bizKey, fp, RejectReason.INVALID_AMOUNT,
                    "手续费不能为负: " + fee);
        }
        Account account = accountRepository.findById(request.accountId()).orElse(null);
        if (account == null) {
            return rejectAndStore(bizKey, fp, RejectReason.ACCOUNT_NOT_FOUND,
                    "账户不存在: " + request.accountId());
        }
        if (account.getCurrency() != currency) {
            return rejectAndStore(bizKey, fp, RejectReason.CURRENCY_MISMATCH,
                    "支付币种 " + currency + " 与账户币种 " + account.getCurrency() + " 不符");
        }
        BigDecimal need = request.amount().add(fee);
        if (account.getAvailable().compareTo(need) < 0) {
            return rejectAndStore(bizKey, fp, RejectReason.INSUFFICIENT_BALANCE,
                    "可用余额不足：需冻结(本金+手续费)=" + need + "，可用=" + account.getAvailable());
        }

        // ---- 首次且校验通过：生成指令、冻结资金、登记幂等成功结果 ----
        String paymentId = "PAY-" + paymentSequence.incrementAndGet();
        Instant now = Instant.now();
        Payment payment = new Payment(paymentId, bizKey, account.getId(), currency,
                request.amount(), fee, PaymentStatus.ACCEPTED,
                BigDecimal.ZERO, now, null, null);
        try {
            // 冻结在账户锁内做权威余额校验（并发下预检快照可能已过期），
            // 锁内余额不足时同样转成可区分的拒绝结果并登记幂等，而不是让异常逃逸
            ledgerService.freeze(account.getId(), paymentId, need,
                    "受理冻结 本金=" + request.amount() + " 手续费=" + fee);
        } catch (InsufficientBalanceException e) {
            return rejectAndStore(bizKey, fp, RejectReason.INSUFFICIENT_BALANCE, e.getMessage());
        }
        paymentRepository.save(payment);
        idempotencyRepository.putIfAbsent(bizKey,
                new IdempotentRecord(bizKey, fp, true, paymentId, null, now));
        return new AcceptResult(bizKey, paymentId, true, false, null, "受理成功，已冻结 " + need);
    }

    private AcceptResult rejectAndStore(String bizKey, String fp, RejectReason reason, String message) {
        idempotencyRepository.putIfAbsent(bizKey,
                new IdempotentRecord(bizKey, fp, false, null, reason, Instant.now()));
        return new AcceptResult(bizKey, null, false, false, reason, message);
    }

    private AcceptResult replay(IdempotentRecord record, String fp) {
        if (!record.getRequestFingerprint().equals(fp)) {
            throw new IdempotencyConflictException(
                    "业务号 " + record.getBizKey() + " 已提交过不同内容的请求，拒绝重复提交");
        }
        if (record.isAccepted()) {
            return new AcceptResult(record.getBizKey(), record.getPaymentId(), true, true, null,
                    "重复提交：返回首次受理结果，未再次扣款");
        }
        return new AcceptResult(record.getBizKey(), null, false, true, record.getRejectReason(),
                "重复提交：返回首次拒绝结果，原因=" + record.getRejectReason());
    }

    /**
     * 冲正：按当前状态回退资金，且只允许合法状态流转。
     * SETTLED 冲正：已结算本金从在途退回（手续费已出账，不退，文档中明确）；
     * PARTIALLY_SETTLED 冲正：在途部分退回 + 冻结中的未结算本金与手续费解冻；
     * ACCEPTED 冲正：冻结的本金与手续费全部解冻。
     */
    public Payment reverseByBizKey(String bizKey, String reason) {
        synchronized (lockOf(bizKey)) {
            Payment payment = paymentRepository.findByBizKey(bizKey)
                    .orElseThrow(() -> new BusinessException("支付指令不存在: " + bizKey));

            BigDecimal settled = payment.getSettledAmount();
            boolean feeCharged = payment.getStatus() == PaymentStatus.SETTLED;

            // 状态机先校验（CONFIRMED / REVERSED 会直接拒绝）
            payment.reverse(Instant.now());

            if (settled.signum() > 0) {
                ledgerService.reverse(payment.getAccountId(), payment.getId(), settled,
                        "冲正在途本金: " + reason);
            }
            BigDecimal unfrozenPrincipal = payment.getUnsettledAmount();
            BigDecimal unfreezeFee = feeCharged ? BigDecimal.ZERO : payment.getFee();
            BigDecimal unfreezeTotal = unfrozenPrincipal.add(unfreezeFee);
            if (unfreezeTotal.signum() > 0) {
                ledgerService.unfreeze(payment.getAccountId(), payment.getId(), unfreezeTotal,
                        "冲正解冻 未结算本金=" + unfrozenPrincipal
                                + (unfreezeFee.signum() > 0 ? " 手续费=" + unfreezeFee : ""));
            }
            paymentRepository.save(payment);
            return payment;
        }
    }

    public Optional<Payment> findByBizKey(String bizKey) {
        return paymentRepository.findByBizKey(bizKey);
    }
}
