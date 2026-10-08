package com.github.feng111627.paymentreconciliationledgerjava.tests;

import com.github.feng111627.paymentreconciliationledgerjava.domain.*;
import com.github.feng111627.paymentreconciliationledgerjava.dto.AcceptRequest;
import com.github.feng111627.paymentreconciliationledgerjava.dto.ItemResult;
import com.github.feng111627.paymentreconciliationledgerjava.dto.ReconcileResult;
import com.github.feng111627.paymentreconciliationledgerjava.dto.SettlementResult;
import com.github.feng111627.paymentreconciliationledgerjava.error.IllegalStateTransitionException;
import com.github.feng111627.paymentreconciliationledgerjava.error.PaymentValidationException;
import com.github.feng111627.paymentreconciliationledgerjava.tests.support.BusinessScenario;
import com.github.feng111627.paymentreconciliationledgerjava.tests.support.LedgerTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@BusinessScenario("状态流转与账本守恒")
class StateTransitionAndLedgerTest extends LedgerTestBase {

    private static final String ACC = "ACC-CNY";

    private void accept(String bizKey, String amount, String fee) {
        paymentService.accept(new AcceptRequest(bizKey, ACC,
                new BigDecimal(amount), "CNY", new BigDecimal(fee)));
    }

    private SettlementResult settle(String batchId, String bizKey, String amount) {
        return settlementService.settleBatch(new SettlementBatch(batchId,
                List.of(new SettlementBatch.Item(bizKey, new BigDecimal(amount))), Instant.now()));
    }

    private Account account() {
        return accountService.require(ACC);
    }

    private static BigDecimal total(Account a) {
        return a.getAvailable().add(a.getFrozen()).add(a.getInflight());
    }

    @Test
    @DisplayName("部分结算：状态 ACCEPTED -> PARTIALLY_SETTLED -> SETTLED，口径与流水逐笔对应")
    void partialSettlementFlow() {
        accept("BIZ-PS", "100.00", "2.00");
        assertEquals(PaymentStatus.ACCEPTED, paymentService.findByBizKey("BIZ-PS").orElseThrow().getStatus());
        assertEquals(new BigDecimal("1000.00"), total(account())); // 冻结仍在账户总额内

        ItemResult r1 = settle("S-PS-1", "BIZ-PS", "40.00").items().get(0);
        assertEquals(PaymentStatus.PARTIALLY_SETTLED, r1.status());
        assertEquals(new BigDecimal("40.00"), r1.settledTotal());
        assertEquals(new BigDecimal("62.00"), account().getFrozen(),
                "冻结=未结算本金60+手续费2");
        assertEquals(new BigDecimal("40.00"), account().getInflight());

        ItemResult r2 = settle("S-PS-2", "BIZ-PS", "60.00").items().get(0);
        assertEquals(PaymentStatus.SETTLED, r2.status());
        assertEquals(new BigDecimal("100.00"), r2.settledTotal());
        // 全额结清时手续费出账：冻结中 60 本金 -> 在途；2 手续费 -> 账户外
        assertEquals(new BigDecimal("0.00"), account().getFrozen());
        assertEquals(new BigDecimal("100.00"), account().getInflight());
        assertEquals(new BigDecimal("998.00"), total(account()),
                "手续费 2.00 已出账；本金 100 仍在在途，账户总额=初始1000-手续费2");
    }

    @Test
    @DisplayName("累计结算超过指令金额被拒绝：不多扣冻结、状态不变")
    void overSettlementRejected() {
        accept("BIZ-OS", "100.00", "0.00");
        settle("S-OS-1", "BIZ-OS", "60.00");
        assertThrows(PaymentValidationException.class, () -> settle("S-OS-2", "BIZ-OS", "60.00"));
        Payment p = paymentService.findByBizKey("BIZ-OS").orElseThrow();
        assertEquals(PaymentStatus.PARTIALLY_SETTLED, p.getStatus());
        assertEquals(new BigDecimal("60.00"), p.getSettledAmount());
        assertEquals(new BigDecimal("40.00"), account().getFrozen());
    }

    @Test
    @DisplayName("ACCEPTED 阶段冲正：冻结的本金+手续费全部退回可用，不重复扣款")
    void reverseAtAcceptedRefundsAllFrozen() {
        accept("BIZ-RA", "100.00", "2.00");
        Payment reversed = paymentService.reverseByBizKey("BIZ-RA", "测试受理后冲正");

        assertEquals(PaymentStatus.REVERSED, reversed.getStatus());
        assertEquals(new BigDecimal("1000.00"), total(account()), "未出账，总额应完全恢复");
        assertEquals(new BigDecimal("1000.00"), account().getAvailable());
        assertEquals(new BigDecimal("0.00"), account().getFrozen());
        assertEquals(new BigDecimal("0.00"), account().getInflight());
        // 流水：FREEZE 102 + UNFREEZE 102
        assertEquals(2, ledgerRepository.findByPaymentId(reversed.getId()).size());
    }

    @Test
    @DisplayName("部分结算后冲正：在途部分退回 + 未结算本金与手续费解冻，账目守恒")
    void reverseAfterPartialSettlement() {
        accept("BIZ-RP", "100.00", "2.00");
        settle("S-RP", "BIZ-RP", "40.00"); // 部分结算不收手续费
        Payment reversed = paymentService.reverseByBizKey("BIZ-RP", "部分结算后冲正");

        assertEquals(PaymentStatus.REVERSED, reversed.getStatus());
        assertEquals(new BigDecimal("1000.00"), total(account()), "手续费尚未出账，总额恢复");
        assertEquals(new BigDecimal("1000.00"), account().getAvailable());
        assertEquals(new BigDecimal("0.00"), account().getFrozen());
        assertEquals(new BigDecimal("0.00"), account().getInflight());
    }

    @Test
    @DisplayName("全额结算后对账差异冲正：在途本金退回，已收手续费不退（规则在文档明确）")
    void reverseAfterFullSettlementKeepsFee() {
        accept("BIZ-RF", "100.00", "2.00");
        settle("S-RF", "BIZ-RF", "100.00"); // 全额：100 进在途，2 手续费出账
        Payment reversed = paymentService.reverseByBizKey("BIZ-RF", "全额后冲正");

        assertEquals(PaymentStatus.REVERSED, reversed.getStatus());
        assertEquals(new BigDecimal("998.00"), account().getAvailable(),
                "本金 100 退回可用；手续费 2 已出账不退");
        assertEquals(new BigDecimal("0.00"), account().getFrozen());
        assertEquals(new BigDecimal("0.00"), account().getInflight());
    }

    @Test
    @DisplayName("对账一致确认：SETTLED -> CONFIRMED，在途资金出账，总额对应减少")
    void confirmAfterMatchedReconciliation() {
        accept("BIZ-CF", "100.00", "2.00");
        settle("S-CF", "BIZ-CF", "100.00");
        ReconcileResult r = reconciliationService.reconcile(
                new StatementBatch("ST-CF",
                        List.of(new StatementEntry("BIZ-CF", new BigDecimal("100.00"), CurrencyCode.CNY)),
                        Instant.now(), false, null), true);

        assertEquals(EntryConclusion.MATCHED, r.entries().get(0).conclusion());
        assertEquals(PaymentStatus.CONFIRMED,
                paymentService.findByBizKey("BIZ-CF").orElseThrow().getStatus());
        assertEquals(new BigDecimal("898.00"), total(account()),
                "确认出账 100 + 手续费 2 离开账户");
        assertTrue(r.actions().get(0).contains("CONFIRMED"));
    }

    @Test
    @DisplayName("非法状态流转被明确拒绝：CONFIRMED/REVERSED 不可再结算、再冲正")
    void illegalTransitionsRejected() {
        accept("BIZ-IL", "100.00", "0.00");
        settle("S-IL-1", "BIZ-IL", "100.00");
        reconciliationService.reconcile(
                new StatementBatch("ST-IL",
                        List.of(new StatementEntry("BIZ-IL", new BigDecimal("100.00"), CurrencyCode.CNY)),
                        Instant.now(), false, null), true);
        // 已 CONFIRMED：再结算、再冲正都必须拒绝
        assertThrows(IllegalStateTransitionException.class, () -> settle("S-IL-2", "BIZ-IL", "1.00"));
        assertThrows(IllegalStateTransitionException.class,
                () -> paymentService.reverseByBizKey("BIZ-IL", "终态后冲正"));

        accept("BIZ-IL2", "10.00", "0.00");
        paymentService.reverseByBizKey("BIZ-IL2", "冲正一次");
        assertThrows(IllegalStateTransitionException.class,
                () -> paymentService.reverseByBizKey("BIZ-IL2", "重复冲正"));
    }

    @Test
    @DisplayName("同一结算批次重复提交：批次幂等，不重复记账")
    void duplicateSettlementBatchIsIdempotent() {
        accept("BIZ-BD", "100.00", "0.00");
        SettlementBatch batch = new SettlementBatch("SAME-BATCH",
                List.of(new SettlementBatch.Item("BIZ-BD", new BigDecimal("100.00"))), Instant.now());

        SettlementResult first = settlementService.settleBatch(batch);
        SettlementResult again = settlementService.settleBatch(batch);
        assertFalse(first.replayed());
        assertTrue(again.replayed());
        assertEquals(PaymentStatus.SETTLED,
                paymentService.findByBizKey("BIZ-BD").orElseThrow().getStatus());
        assertEquals(new BigDecimal("100.00"), account().getInflight());
        // 仅一条 SETTLE 流水
        long settleEntries = ledgerRepository.findAll().stream()
                .filter(e -> e.getType() == LedgerEntryType.SETTLE).count();
        assertEquals(1, settleEntries);
    }
}
