package com.github.feng111627.paymentreconciliationledgerjava.service;

import com.github.feng111627.paymentreconciliationledgerjava.domain.*;
import com.github.feng111627.paymentreconciliationledgerjava.dto.ReconEntryResult;
import com.github.feng111627.paymentreconciliationledgerjava.dto.ReconcileResult;
import com.github.feng111627.paymentreconciliationledgerjava.error.IllegalStateTransitionException;
import com.github.feng111627.paymentreconciliationledgerjava.error.PaymentValidationException;
import com.github.feng111627.paymentreconciliationledgerjava.store.PaymentRepository;
import com.github.feng111627.paymentreconciliationledgerjava.store.StatementRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 对账服务。
 *
 * 判定规则（不以任何一方默认为准，逐字段给出可区分结论）：
 * 1) 字段缺失（bizKey/金额/币种为空）或币种无法识别      -> MALFORMED_ENTRY；
 * 2) 对账单有、本地没有                                -> LOCAL_MISSING；
 * 3) 本地有、对账单没有                                -> STATEMENT_MISSING；
 * 4) 币种可识别但与本地支付币种不同                      -> CURRENCY_MISMATCH；
 * 5) 金额不一致（按两位小数比较）                        -> AMOUNT_MISMATCH；
 * 6) 全部一致                                         -> MATCHED。
 *
 * 乱序安全：始终按 bizKey 匹配，与条目到达顺序无关；本地先结算后对账、对账单先到均可解释。
 * 批次幂等：同一 batchId 重复对账直接返回首次结论，不重复记账、不重复冲正，结论可复现。
 * autoActions=true 时才执行处置：MATCHED+SETTLED 自动确认出账；金额/币种不符自动冲正。
 */
@Service
public class ReconciliationService {

    private final StatementRepository statementRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentService paymentService;
    private final LedgerService ledgerService;

    private final ConcurrentMap<String, Object> batchLocks = new ConcurrentHashMap<>();

    public ReconciliationService(StatementRepository statementRepository,
                                 PaymentRepository paymentRepository,
                                 PaymentService paymentService,
                                 LedgerService ledgerService) {
        this.statementRepository = statementRepository;
        this.paymentRepository = paymentRepository;
        this.paymentService = paymentService;
        this.ledgerService = ledgerService;
    }

    public ReconcileResult reconcile(StatementBatch batch, boolean autoActions) {
        if (batch == null || batch.getBatchId() == null || batch.getBatchId().isBlank()) {
            throw new PaymentValidationException("对账单批次号不能为空", null);
        }
        String batchId = batch.getBatchId();
        synchronized (batchLocks.computeIfAbsent(batchId, k -> new Object())) {
            var stored = statementRepository.findBatch(batchId);
            if (stored.isEmpty()) {
                statementRepository.saveBatch(batch);
            } else if (stored.get().isReconciled()) {
                // 批次重复对账：原样重放首次结论，不再产生任何账务动作
                return replay(batchId);
            }

            List<ReconEntryResult> results = evaluate(batch);
            List<String> actions = autoActions ? applyActions(results) : List.of();

            batch.markReconciled(Instant.now());
            statementRepository.saveBatch(batch);
            for (ReconEntryResult r : results) {
                if (!statementRepository.existsRecord(batchId, r.bizKey())) {
                    statementRepository.appendRecord(new ReconciliationRecord(
                            batchId, r.bizKey(), r.conclusion(), r.detail(), Instant.now()));
                }
            }
            return new ReconcileResult(batchId, false, sort(results), actions);
        }
    }

    /** 逐行评估；行的返回顺序按 bizKey 排序，保证乱序输入下结论可复现。 */
    private List<ReconEntryResult> evaluate(StatementBatch batch) {
        // 结果按 bizKey 去重：批内重复条目沿用首次结论，不重复评估、不重复处置
        Map<String, ReconEntryResult> byKey = new TreeMap<>();
        Set<String> seenInStatement = new HashSet<>();

        for (StatementEntry e : batch.getEntries()) {
            String bizKey = e.getBizKey();
            boolean duplicate = bizKey != null && !bizKey.isBlank() && byKey.containsKey(bizKey);
            ReconEntryResult result = evaluateOne(bizKey, e, duplicate);
            byKey.putIfAbsent(result.bizKey(), result);
            if (bizKey != null && !bizKey.isBlank()) {
                seenInStatement.add(bizKey);
            }
        }

        // 本地存在、对账单缺失的条目
        List<Payment> locals = new ArrayList<>(paymentRepository.findAll());
        locals.sort(Comparator.comparing(Payment::getBizKey));
        for (Payment p : locals) {
            if (!seenInStatement.contains(p.getBizKey())) {
                byKey.putIfAbsent(p.getBizKey(), new ReconEntryResult(
                        p.getBizKey(), EntryConclusion.STATEMENT_MISSING,
                        "本地存在支付指令但对账单缺少该条目，状态=" + p.getStatus()));
            }
        }
        return new ArrayList<>(byKey.values());
    }

    private ReconEntryResult evaluateOne(String bizKey, StatementEntry e, boolean duplicate) {
        String suffix = duplicate ? "（批内重复条目，结论沿用首次）" : "";

        // 1) 畸形：字段缺失
        if (bizKey == null || bizKey.isBlank()) {
            return new ReconEntryResult("<blank-biz-key>", EntryConclusion.MALFORMED_ENTRY,
                    "对账单条目缺少业务号 bizKey" + suffix);
        }
        if (e.getAmount() == null) {
            return new ReconEntryResult(bizKey, EntryConclusion.MALFORMED_ENTRY,
                    "对账单条目缺少金额 amount" + suffix);
        }
        if (e.getCurrency() == null) {
            return new ReconEntryResult(bizKey, EntryConclusion.MALFORMED_ENTRY,
                    "对账单条目缺少币种 currency" + suffix);
        }

        // 2) 本地缺失
        Optional<Payment> local = paymentRepository.findByBizKey(bizKey);
        if (local.isEmpty()) {
            return new ReconEntryResult(bizKey, EntryConclusion.LOCAL_MISSING,
                    "对账单有该条目但本地无支付指令" + suffix);
        }
        Payment payment = local.get();

        // 3) 币种不符
        if (payment.getCurrency() != e.getCurrency()) {
            return new ReconEntryResult(bizKey, EntryConclusion.CURRENCY_MISMATCH,
                    "币种不符：对账单=" + e.getCurrency() + "，本地=" + payment.getCurrency() + suffix);
        }

        // 4) 金额不一致
        BigDecimal statementAmount = e.getAmount().setScale(Money.SCALE);
        if (statementAmount.compareTo(payment.getAmount()) != 0) {
            return new ReconEntryResult(bizKey, EntryConclusion.AMOUNT_MISMATCH,
                    "金额不一致：对账单=" + statementAmount + "，本地=" + payment.getAmount()
                            + "（本地状态=" + payment.getStatus() + "）" + suffix);
        }

        // 5) 一致
        String note = payment.getStatus() == PaymentStatus.SETTLED
                ? "金额币种一致，本地已全额结算"
                : "金额币种一致，本地当前状态=" + payment.getStatus() + "（对账单可能先到）";
        return new ReconEntryResult(bizKey, EntryConclusion.MATCHED, note + suffix);
    }

    private List<String> applyActions(List<ReconEntryResult> results) {
        List<String> actions = new ArrayList<>();
        for (ReconEntryResult r : results) {
            Optional<Payment> opt = paymentRepository.findByBizKey(r.bizKey());
            if (opt.isEmpty()) {
                continue;
            }
            Payment payment = opt.get();
            switch (r.conclusion()) {
                case MATCHED -> {
                    if (payment.getStatus() == PaymentStatus.SETTLED) {
                        payment.confirm(Instant.now());
                        ledgerService.confirm(payment.getAccountId(), payment.getId(),
                                payment.getAmount(), "对账一致，确认出账");
                        paymentRepository.save(payment);
                        actions.add(r.bizKey() + ": MATCHED -> CONFIRMED（在途出账）");
                    }
                }
                case AMOUNT_MISMATCH, CURRENCY_MISMATCH -> {
                    try {
                        paymentService.reverseByBizKey(r.bizKey(),
                                "对账差异 " + r.conclusion() + " - " + r.detail());
                        actions.add(r.bizKey() + ": " + r.conclusion() + " -> REVERSED（已冲正）");
                    } catch (IllegalStateTransitionException ex) {
                        actions.add(r.bizKey() + ": " + r.conclusion()
                                + " 但指令处于终态 " + ex.getFrom() + "，不重复冲正");
                    }
                }
                default -> { /* LOCAL/STATEMENT_MISSING、MALFORMED 不自动处置，只出结论 */ }
            }
        }
        return actions;
    }

    private ReconcileResult replay(String batchId) {
        List<ReconEntryResult> rows = statementRepository.findRecordsByBatch(batchId).stream()
                .map(r -> new ReconEntryResult(r.getBizKey(), r.getConclusion(), r.getDetail()))
                .sorted(Comparator.comparing(ReconEntryResult::bizKey))
                .toList();
        return new ReconcileResult(batchId, true, rows, List.of());
    }

    private List<ReconEntryResult> sort(List<ReconEntryResult> results) {
        List<ReconEntryResult> copy = new ArrayList<>(results);
        copy.sort(Comparator.comparing(ReconEntryResult::bizKey));
        return copy;
    }
}
