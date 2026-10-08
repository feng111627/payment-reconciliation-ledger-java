package com.github.feng111627.paymentreconciliationledgerjava.tests;

import com.github.feng111627.paymentreconciliationledgerjava.domain.*;
import com.github.feng111627.paymentreconciliationledgerjava.dto.AcceptRequest;
import com.github.feng111627.paymentreconciliationledgerjava.dto.ReconEntryResult;
import com.github.feng111627.paymentreconciliationledgerjava.dto.ReconcileResult;
import com.github.feng111627.paymentreconciliationledgerjava.tests.support.BusinessScenario;
import com.github.feng111627.paymentreconciliationledgerjava.tests.support.LedgerTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@BusinessScenario("乱序与畸形对账")
class ReconciliationDisorderTest extends LedgerTestBase {

    private void acceptAndSettle(String bizKey, String amount) {
        paymentService.accept(new AcceptRequest(bizKey, "ACC-CNY",
                new BigDecimal(amount), "CNY", BigDecimal.ZERO));
        settlementService.settleBatch(new SettlementBatch("SET-" + bizKey,
                List.of(new SettlementBatch.Item(bizKey, new BigDecimal(amount))), Instant.now()));
    }

    private StatementBatch batch(String batchId, List<StatementEntry> entries) {
        return new StatementBatch(batchId, entries, Instant.now(), false, null);
    }

    private StatementEntry entry(String bizKey, String amount, CurrencyCode currency) {
        return new StatementEntry(bizKey, amount == null ? null : new BigDecimal(amount), currency);
    }

    private ReconEntryResult of(ReconcileResult r, String bizKey) {
        return r.entries().stream().filter(e -> e.bizKey().equals(bizKey)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("条目乱序到达不影响结论：按 bizKey 匹配，结果顺序稳定可复现")
    void outOfOrderEntriesProduceSameConclusions() {
        acceptAndSettle("BIZ-A", "100.00");
        acceptAndSettle("BIZ-B", "200.00");

        List<StatementEntry> order1 = List.of(
                entry("BIZ-B", "200.00", CurrencyCode.CNY),
                entry("BIZ-A", "100.00", CurrencyCode.CNY));
        List<StatementEntry> order2 = List.of(
                entry("BIZ-A", "100.00", CurrencyCode.CNY),
                entry("BIZ-B", "200.00", CurrencyCode.CNY));

        ReconcileResult r1 = reconciliationService.reconcile(batch("ST-DIS-1", order1), false);
        ReconcileResult r2 = reconciliationService.reconcile(batch("ST-DIS-2", order2), false);

        assertEquals("BIZ-A", r1.entries().get(0).bizKey(), "结论必须按 bizKey 排序输出");
        assertEquals(EntryConclusion.MATCHED, of(r1, "BIZ-A").conclusion());
        assertEquals(EntryConclusion.MATCHED, of(r1, "BIZ-B").conclusion());
        assertEquals(r1.entries(), r2.entries(), "两种到达顺序的结论完全一致");
    }

    @Test
    @DisplayName("对账单先到、本地后结算：不默认以任何一方为准，状态可解释")
    void statementArrivesBeforeSettlement() {
        // 本地只受理未结算（在冻结中），对账单金额一致
        paymentService.accept(new AcceptRequest("BIZ-EARLY", "ACC-CNY",
                new BigDecimal("30.00"), "CNY", BigDecimal.ZERO));
        ReconcileResult r = reconciliationService.reconcile(
                batch("ST-EARLY", List.of(entry("BIZ-EARLY", "30.00", CurrencyCode.CNY))), false);
        assertEquals(EntryConclusion.MATCHED, of(r, "BIZ-EARLY").conclusion());
        assertTrue(of(r, "BIZ-EARLY").detail().contains("ACCEPTED"),
                "结论明细必须说明本地仍在 ACCEPTED，不提前确认出账");
        assertEquals(PaymentStatus.ACCEPTED,
                paymentService.findByBizKey("BIZ-EARLY").orElseThrow().getStatus());
    }

    @Test
    @DisplayName("金额不一致：明确给 AMOUNT_MISMATCH 且不自动改账")
    void amountMismatchIsDistinctAndNotAutoCorrected() {
        acceptAndSettle("BIZ-M", "100.00");
        ReconcileResult r = reconciliationService.reconcile(batch("ST-AMT",
                List.of(entry("BIZ-M", "99.00", CurrencyCode.CNY))), false);
        assertEquals(EntryConclusion.AMOUNT_MISMATCH, of(r, "BIZ-M").conclusion());
        assertEquals(PaymentStatus.SETTLED,
                paymentService.findByBizKey("BIZ-M").orElseThrow().getStatus(),
                "未开启自动处置时状态不被改变");
    }

    @Test
    @DisplayName("币种不符：明确给 CURRENCY_MISMATCH，与金额不一致可区分")
    void currencyMismatchIsDistinct() {
        acceptAndSettle("BIZ-C", "100.00");
        ReconcileResult r = reconciliationService.reconcile(batch("ST-CCY",
                List.of(entry("BIZ-C", "100.00", CurrencyCode.USD))), false);
        assertEquals(EntryConclusion.CURRENCY_MISMATCH, of(r, "BIZ-C").conclusion());
    }

    @Test
    @DisplayName("对账单有、本地无：LOCAL_MISSING；本地有、对账单无：STATEMENT_MISSING")
    void missingOnEitherSide() {
        acceptAndSettle("BIZ-LOCAL", "10.00");
        ReconcileResult r = reconciliationService.reconcile(batch("ST-MISS",
                List.of(entry("BIZ-REMOTE", "10.00", CurrencyCode.CNY))), false);
        assertEquals(EntryConclusion.LOCAL_MISSING, of(r, "BIZ-REMOTE").conclusion());
        assertEquals(EntryConclusion.STATEMENT_MISSING, of(r, "BIZ-LOCAL").conclusion());
    }

    @Test
    @DisplayName("字段缺失/无法解析：分别给出 MALFORMED_ENTRY 明细，不静默丢弃")
    void malformedEntriesAreReported() {
        acceptAndSettle("BIZ-OK", "10.00");
        // bizKey 缺失
        StatementEntry noKey = entry(null, "10.00", CurrencyCode.CNY);
        // 金额缺失
        StatementEntry noAmount = entry("BIZ-X1", null, CurrencyCode.CNY);
        // 币种缺失（控制器层会把无法识别的币种也解析为 null）
        StatementEntry noCurrency = entry("BIZ-X2", "10.00", null);
        ReconcileResult r = reconciliationService.reconcile(
                batch("ST-MAL", List.of(noKey, noAmount, noCurrency,
                        entry("BIZ-OK", "10.00", CurrencyCode.CNY))), false);

        assertEquals(EntryConclusion.MALFORMED_ENTRY, r.entries().get(0).conclusion());
        assertEquals(EntryConclusion.MALFORMED_ENTRY, of(r, "BIZ-X1").conclusion());
        assertTrue(of(r, "BIZ-X1").detail().contains("金额"));
        assertEquals(EntryConclusion.MALFORMED_ENTRY, of(r, "BIZ-X2").conclusion());
        assertTrue(of(r, "BIZ-X2").detail().contains("币种"));
        assertEquals(EntryConclusion.MATCHED, of(r, "BIZ-OK").conclusion());
    }

    @Test
    @DisplayName("同一份对账单重复对账：返回首次结论(replayed)，不重复记账、结论可复现")
    void duplicateReconciliationIsIdempotent() {
        acceptAndSettle("BIZ-DUP", "100.00");
        StatementBatch b = batch("ST-DUP",
                List.of(entry("BIZ-DUP", "99.00", CurrencyCode.CNY)));

        ReconcileResult first = reconciliationService.reconcile(b, true);
        assertEquals(EntryConclusion.AMOUNT_MISMATCH, of(first, "BIZ-DUP").conclusion());
        assertEquals(PaymentStatus.REVERSED,
                paymentService.findByBizKey("BIZ-DUP").orElseThrow().getStatus());
        int ledgerSizeAfterFirst = ledgerRepository.findAll().size();

        // 再次对账：必须重放首次结论，不允许第二次触发冲正
        ReconcileResult again = reconciliationService.reconcile(b, true);
        assertTrue(again.replayed());
        assertEquals(EntryConclusion.AMOUNT_MISMATCH, of(again, "BIZ-DUP").conclusion());
        assertEquals(ledgerSizeAfterFirst, ledgerRepository.findAll().size(),
                "重复对账不得重复记账/重复冲正");
        assertTrue(again.actions().isEmpty());
    }

    @Test
    @DisplayName("批内重复条目：只评估一次，结论标注为重复沿用")
    void duplicateEntriesWithinBatch() {
        acceptAndSettle("BIZ-W", "100.00");
        ReconcileResult r = reconciliationService.reconcile(batch("ST-W",
                List.of(entry("BIZ-W", "100.00", CurrencyCode.CNY),
                        entry("BIZ-W", "100.00", CurrencyCode.CNY))), false);
        assertEquals(1, r.entries().size());
    }
}
