package com.github.feng111627.paymentreconciliationledgerjava.service;

import com.github.feng111627.paymentreconciliationledgerjava.domain.Payment;
import com.github.feng111627.paymentreconciliationledgerjava.domain.PaymentStatus;
import com.github.feng111627.paymentreconciliationledgerjava.domain.SettlementBatch;
import com.github.feng111627.paymentreconciliationledgerjava.dto.ItemResult;
import com.github.feng111627.paymentreconciliationledgerjava.dto.SettlementResult;
import com.github.feng111627.paymentreconciliationledgerjava.error.BusinessException;
import com.github.feng111627.paymentreconciliationledgerjava.error.PaymentValidationException;
import com.github.feng111627.paymentreconciliationledgerjava.store.PaymentRepository;
import com.github.feng111627.paymentreconciliationledgerjava.store.SettlementRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 批量结算。
 * 1) 按 batchId 幂等：同批次重复提交（含并发）返回首次结果，不重复记账；
 * 2) 每项允许部分结算；累计超过指令金额或对终态指令结算都被明确拒绝；
 * 3) 全额结清的同一动作内收取手续费（冻结 -> 出账），并把状态推进到 SETTLED；
 * 4) 批内按 bizKey 排序处理，避免跨账户交叉加锁，结果对乱序输入可复现。
 */
@Service
public class SettlementService {

    private final SettlementRepository settlementRepository;
    private final PaymentRepository paymentRepository;
    private final LedgerService ledgerService;
    private final PaymentService paymentService;

    public SettlementService(SettlementRepository settlementRepository,
                             PaymentRepository paymentRepository,
                             LedgerService ledgerService,
                             PaymentService paymentService) {
        this.settlementRepository = settlementRepository;
        this.paymentRepository = paymentRepository;
        this.ledgerService = ledgerService;
        this.paymentService = paymentService;
    }

    public SettlementResult settleBatch(SettlementBatch batch) {
        if (batch == null || batch.getBatchId() == null || batch.getBatchId().isBlank()) {
            throw new PaymentValidationException("结算批次号不能为空", null);
        }
        // 批次幂等：已存在则原样重放首次结果（依据账本与指令当前值重建，结论可复现）
        if (settlementRepository.findById(batch.getBatchId()).isPresent()) {
            return replay(batch);
        }
        SettlementBatch existing = settlementRepository.saveIfAbsent(batch);
        if (existing != null) {
            return replay(existing);
        }

        List<SettlementBatch.Item> ordered = new ArrayList<>(batch.getItems());
        ordered.sort(Comparator.comparing(SettlementBatch.Item::bizKey));

        List<ItemResult> results = new ArrayList<>();
        for (SettlementBatch.Item item : ordered) {
            results.add(settleOne(item));
        }
        return new SettlementResult(batch.getBatchId(), false, results);
    }

    private ItemResult settleOne(SettlementBatch.Item item) {
        if (item.bizKey() == null || item.bizKey().isBlank()) {
            throw new PaymentValidationException("结算项业务号不能为空", null);
        }
        if (item.amount() == null || item.amount().signum() <= 0) {
            throw new PaymentValidationException(
                    "结算金额必须为正数: bizKey=" + item.bizKey() + " amount=" + item.amount(), null);
        }

        Payment payment = paymentRepository.findByBizKey(item.bizKey())
                .orElseThrow(() -> new BusinessException("支付指令不存在: " + item.bizKey()));

        PaymentStatus before = payment.getStatus();
        if (before.isTerminal()) {
            throw new com.github.feng111627.paymentreconciliationledgerjava.error
                    .IllegalStateTransitionException(
                    "终态指令不可再结算: bizKey=" + item.bizKey() + " status=" + before,
                    before, before);
        }

        BigDecimal settledNow = item.amount().setScale(2);
        boolean full;
        // 指令自身先校验金额与状态，得到目标状态（非法流转在此被拒绝）
        PaymentStatus target = payment.registerSettlement(settledNow, Instant.now());
        full = target == PaymentStatus.SETTLED;

        // 资金：本金 frozen -> inflight
        ledgerService.settle(payment.getAccountId(), payment.getId(), settledNow,
                "批量结算 本金=" + settledNow);
        // 全额结清时手续费出账（部分结算不收手续费）
        if (full && payment.getFee().signum() > 0) {
            ledgerService.fee(payment.getAccountId(), payment.getId(), payment.getFee(),
                    "全额结算手续费出账");
        }
        paymentRepository.save(payment);

        return new ItemResult(item.bizKey(), settledNow,
                payment.getSettledAmount(), payment.getStatus());
    }

    /** 重放：重复批次不再次执行，仅返回各指令的当前结算状态。 */
    private SettlementResult replay(SettlementBatch batch) {
        List<ItemResult> results = new ArrayList<>();
        List<SettlementBatch.Item> ordered = new ArrayList<>(batch.getItems());
        ordered.sort(Comparator.comparing(SettlementBatch.Item::bizKey));
        for (SettlementBatch.Item item : ordered) {
            Payment payment = paymentService.findByBizKey(item.bizKey())
                    .orElseThrow(() -> new BusinessException("支付指令不存在: " + item.bizKey()));
            results.add(new ItemResult(item.bizKey(), BigDecimal.ZERO,
                    payment.getSettledAmount(), payment.getStatus()));
        }
        return new SettlementResult(batch.getBatchId(), true, results);
    }
}
