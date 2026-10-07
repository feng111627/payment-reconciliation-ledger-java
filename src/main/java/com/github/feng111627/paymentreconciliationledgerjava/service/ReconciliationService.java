package com.github.feng111627.paymentreconciliationledgerjava.service;

import com.github.feng111627.paymentreconciliationledgerjava.domain.IllegalTransitionException;
import com.github.feng111627.paymentreconciliationledgerjava.domain.PaymentInstruction;
import com.github.feng111627.paymentreconciliationledgerjava.domain.ReconEntry;
import com.github.feng111627.paymentreconciliationledgerjava.domain.ReconRecord;
import com.github.feng111627.paymentreconciliationledgerjava.domain.ReconResult;
import com.github.feng111627.paymentreconciliationledgerjava.repository.PaymentRepository;
import com.github.feng111627.paymentreconciliationledgerjava.repository.ReconRecordRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 对账服务：与本地对账单逐条比对。
 *
 * 判定规则（按优先级，互不掩盖、可区分）：
 *   1. 必填字段缺失（bizNo/amount/currency） -> MISSING_REQUIRED_FIELDS
 *   2. 同一对账单同一业务号已有结论          -> 复现首次结论（replayed=true），不重复记账
 *   3. 支付指令不存在                        -> PAYMENT_NOT_FOUND
 *   4. 币种不符                              -> CURRENCY_MISMATCH，并触发冲正
 *   5. 金额不一致                            -> AMOUNT_MISMATCH，并触发冲正
 *   6. 全部一致                              -> MATCHED
 *
 * 条目按各自业务号独立判定，乱序到达不影响结论；结论落库后重复对账幂等。
 */
@Service
public class ReconciliationService {
    private final PaymentRepository paymentRepository;
    private final ReconRecordRepository reconRecordRepository;
    private final PaymentService paymentService;

    public ReconciliationService(PaymentRepository paymentRepository,
                                 ReconRecordRepository reconRecordRepository,
                                 PaymentService paymentService) {
        this.paymentRepository = paymentRepository;
        this.reconRecordRepository = reconRecordRepository;
        this.paymentService = paymentService;
    }

    public List<ReconItemResult> reconcile(String statementId, List<ReconEntry> entries) {
        List<ReconItemResult> results = new ArrayList<>();
        for (ReconEntry entry : entries) {
            results.add(reconcileOne(statementId, entry));
        }
        return results;
    }

    private ReconItemResult reconcileOne(String statementId, ReconEntry entry) {
        // 1. 字段缺失：无法定位业务号时不落库（无法复现键），直接返回结论
        if (entry.getBizNo() == null || entry.getBizNo().isBlank()
                || entry.getAmount() == null
                || entry.getCurrency() == null || entry.getCurrency().isBlank()) {
            return new ReconItemResult(entry.getBizNo(), ReconResult.MISSING_REQUIRED_FIELDS,
                    "bizNo/amount/currency must all be present", false);
        }
        String bizNo = entry.getBizNo();
        // 2. 重复对账：复现首次结论，不产生任何副作用
        var existing = reconRecordRepository.find(statementId, bizNo);
        if (existing.isPresent()) {
            ReconRecord r = existing.get();
            return new ReconItemResult(bizNo, r.getResult(), r.getDetail(), true);
        }

        // 3-6. 首次判定
        ReconResult result;
        String detail;
        var paymentOpt = paymentRepository.findByIdempotencyKey(bizNo);
        if (paymentOpt.isEmpty()) {
            result = ReconResult.PAYMENT_NOT_FOUND;
            detail = "no payment with idempotencyKey " + bizNo;
        } else {
            PaymentInstruction p = paymentOpt.get();
            if (!p.getCurrency().equals(entry.getCurrency())) {
                result = ReconResult.CURRENCY_MISMATCH;
                detail = "statement " + entry.getCurrency() + " != payment " + p.getCurrency();
                triggerReversal(bizNo, result);
            } else if (p.getAmount().compareTo(entry.getAmount()) != 0) {
                result = ReconResult.AMOUNT_MISMATCH;
                detail = "statement " + entry.getAmount() + " != payment " + p.getAmount();
                triggerReversal(bizNo, result);
            } else {
                result = ReconResult.MATCHED;
                detail = "amount " + p.getAmount() + " " + p.getCurrency() + " matched";
            }
        }
        reconRecordRepository.putIfAbsent(new ReconRecord(statementId, bizNo, result, detail));
        return new ReconItemResult(bizNo, result, detail, false);
    }

    /** 对账差异触发冲正；已冲正/已拒绝的指令不再重复冲正。 */
    private void triggerReversal(String bizNo, ReconResult cause) {
        try {
            paymentService.reverse(bizNo, "recon:" + cause);
        } catch (IllegalTransitionException ignored) {
            // 已是终态，冲正幂等跳过
        }
    }
}
