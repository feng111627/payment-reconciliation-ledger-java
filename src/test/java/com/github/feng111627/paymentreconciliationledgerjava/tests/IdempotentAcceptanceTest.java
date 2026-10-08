package com.github.feng111627.paymentreconciliationledgerjava.tests;

import com.github.feng111627.paymentreconciliationledgerjava.domain.Account;
import com.github.feng111627.paymentreconciliationledgerjava.domain.Payment;
import com.github.feng111627.paymentreconciliationledgerjava.dto.AcceptRequest;
import com.github.feng111627.paymentreconciliationledgerjava.dto.AcceptResult;
import com.github.feng111627.paymentreconciliationledgerjava.error.IdempotencyConflictException;
import com.github.feng111627.paymentreconciliationledgerjava.domain.RejectReason;
import com.github.feng111627.paymentreconciliationledgerjava.tests.support.BusinessScenario;
import com.github.feng111627.paymentreconciliationledgerjava.tests.support.LedgerTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@BusinessScenario("支付受理与幂等")
class IdempotentAcceptanceTest extends LedgerTestBase {

    private AcceptRequest req(String bizKey, String account, String amount,
                              String currency, String fee) {
        return new AcceptRequest(bizKey, account, new BigDecimal(amount), currency,
                fee == null ? null : new BigDecimal(fee));
    }

    @Test
    @DisplayName("首次受理成功并冻结本金+手续费")
    void firstAcceptanceFreezesPrincipalAndFee() {
        AcceptResult result = paymentService.accept(req("BIZ-1", "ACC-CNY", "100.00", "CNY", "2.00"));

        assertTrue(result.accepted());
        assertFalse(result.replayed());
        assertNull(result.rejectReason());

        Account account = accountService.require("ACC-CNY");
        assertEquals(new BigDecimal("898.00"), account.getAvailable());
        assertEquals(new BigDecimal("102.00"), account.getFrozen());
        assertEquals(1, paymentRepository.findAll().size());
        assertEquals(1, ledgerRepository.findAll().size());
    }

    @Test
    @DisplayName("同一业务号重复提交只生效一次：返回首次结果且不重复扣款/不多记一笔")
    void duplicateSubmitReplaysWithoutDoubleCharge() {
        AcceptResult first = paymentService.accept(req("BIZ-2", "ACC-CNY", "50.00", "CNY", "1.00"));
        AcceptResult second = paymentService.accept(req("BIZ-2", "ACC-CNY", "50.00", "CNY", "1.00"));
        AcceptResult third = paymentService.accept(req("BIZ-2", "ACC-CNY", "50.00", "CNY", "1.00"));

        assertTrue(second.accepted());
        assertTrue(second.replayed());
        assertEquals(first.paymentId(), second.paymentId());
        assertTrue(third.replayed());

        assertEquals(1, paymentRepository.findAll().size());
        assertEquals(1, ledgerRepository.findAll().size());
        Account account = accountService.require("ACC-CNY");
        assertEquals(new BigDecimal("949.00"), account.getAvailable());
        assertEquals(new BigDecimal("51.00"), account.getFrozen());
    }

    @Test
    @DisplayName("同一业务号但请求内容不同：明确拒绝为幂等冲突")
    void sameBizKeyDifferentContentIsConflict() {
        paymentService.accept(req("BIZ-3", "ACC-CNY", "50.00", "CNY", "0.00"));
        assertThrows(IdempotencyConflictException.class,
                () -> paymentService.accept(req("BIZ-3", "ACC-CNY", "60.00", "CNY", "0.00")));
    }

    @Test
    @DisplayName("余额不足：拒绝并给出 INSUFFICIENT_BALANCE，不冻结、不建指令")
    void insufficientBalanceRejected() {
        AcceptResult result = paymentService.accept(req("BIZ-4", "ACC-POOR", "100.00", "CNY", "0.00"));

        assertFalse(result.accepted());
        assertEquals(RejectReason.INSUFFICIENT_BALANCE, result.rejectReason());
        assertEquals(new BigDecimal("10.00"), accountService.require("ACC-POOR").getAvailable());
        assertEquals(0, paymentRepository.findAll().size());
        assertEquals(0, ledgerRepository.findAll().size());
    }

    @Test
    @DisplayName("币种不符：拒绝并给出 CURRENCY_MISMATCH")
    void currencyMismatchRejected() {
        AcceptResult result = paymentService.accept(req("BIZ-5", "ACC-CNY", "10.00", "USD", "0.00"));
        assertFalse(result.accepted());
        assertEquals(RejectReason.CURRENCY_MISMATCH, result.rejectReason());
    }

    @Test
    @DisplayName("不支持/缺失的币种：拒绝并给出 CURRENCY_NOT_SUPPORTED")
    void unsupportedCurrencyRejected() {
        AcceptResult missing = paymentService.accept(req("BIZ-6a", "ACC-CNY", "10.00", null, "0.00"));
        AcceptResult bad = paymentService.accept(req("BIZ-6b", "ACC-CNY", "10.00", "JPY", "0.00"));
        assertEquals(RejectReason.CURRENCY_NOT_SUPPORTED, missing.rejectReason());
        assertEquals(RejectReason.CURRENCY_NOT_SUPPORTED, bad.rejectReason());
    }

    @Test
    @DisplayName("金额非法/负数/手续费为负：拒绝并给出 INVALID_AMOUNT")
    void invalidAmountRejected() {
        AcceptResult zero = paymentService.accept(req("BIZ-7a", "ACC-CNY", "0.00", "CNY", "0.00"));
        AcceptResult negative = paymentService.accept(req("BIZ-7b", "ACC-CNY", "-5.00", "CNY", "0.00"));
        AcceptResult badFee = paymentService.accept(req("BIZ-7c", "ACC-CNY", "5.00", "CNY", "-1.00"));
        List.of(zero, negative, badFee).forEach(r -> {
            assertFalse(r.accepted());
            assertEquals(RejectReason.INVALID_AMOUNT, r.rejectReason());
        });
    }

    @Test
    @DisplayName("账户不存在：拒绝并给出 ACCOUNT_NOT_FOUND")
    void accountNotFoundRejected() {
        AcceptResult result = paymentService.accept(req("BIZ-8", "ACC-X", "10.00", "CNY", "0.00"));
        assertFalse(result.accepted());
        assertEquals(RejectReason.ACCOUNT_NOT_FOUND, result.rejectReason());
    }

    @Test
    @DisplayName("首次失败也登记幂等：用相同内容重试仍返回首次拒绝原因，不变成成功")
    void rejectedFirstCallIsAlsoIdempotent() {
        AcceptResult first = paymentService.accept(req("BIZ-9", "ACC-POOR", "100.00", "CNY", "0.00"));
        // 给账户充值后再次以相同内容提交，仍必须重放首次的“拒绝”结论
        accountService.require("ACC-POOR");
        AcceptResult replay = paymentService.accept(req("BIZ-9", "ACC-POOR", "100.00", "CNY", "0.00"));
        assertFalse(first.accepted());
        assertTrue(replay.replayed());
        assertFalse(replay.accepted());
        assertEquals(RejectReason.INSUFFICIENT_BALANCE, replay.rejectReason());
        assertEquals(0, paymentRepository.findAll().size());
    }
}
