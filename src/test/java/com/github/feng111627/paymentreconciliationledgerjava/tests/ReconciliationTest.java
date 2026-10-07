package com.github.feng111627.paymentreconciliationledgerjava.tests;

import com.github.feng111627.paymentreconciliationledgerjava.domain.Account;
import com.github.feng111627.paymentreconciliationledgerjava.domain.PaymentStatus;
import com.github.feng111627.paymentreconciliationledgerjava.domain.ReconEntry;
import com.github.feng111627.paymentreconciliationledgerjava.domain.ReconResult;
import com.github.feng111627.paymentreconciliationledgerjava.repository.AccountRepository;
import com.github.feng111627.paymentreconciliationledgerjava.repository.PaymentRepository;
import com.github.feng111627.paymentreconciliationledgerjava.repository.ReconRecordRepository;
import com.github.feng111627.paymentreconciliationledgerjava.service.PaymentService;
import com.github.feng111627.paymentreconciliationledgerjava.service.ReconItemResult;
import com.github.feng111627.paymentreconciliationledgerjava.service.ReconciliationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 业务覆盖：本地对账单对账——乱序、畸形、差异、重复对账的结论区分与可复现。 */
@SpringBootTest
class ReconciliationTest {
    private static final Logger log = LoggerFactory.getLogger(ReconciliationTest.class);

    @Autowired PaymentService paymentService;
    @Autowired ReconciliationService reconciliationService;
    @Autowired AccountRepository accountRepository;
    @Autowired PaymentRepository paymentRepository;
    @Autowired ReconRecordRepository reconRecordRepository;

    @BeforeEach
    void setUp() {
        Account acc = new Account("acc-recon", "CNY");
        acc.setBalance(new BigDecimal("1000.00"));
        accountRepository.save(acc);
        log.info("[业务:对账] 初始化账户 acc-recon 余额 1000.00 CNY");
    }

    @Test
    @DisplayName("乱序到达的对账条目：每条独立判定，结论与顺序无关")
    void outOfOrderEntriesReconciledIndependently() {
        log.info("[业务:对账] 用例:乱序对账");
        paymentService.submit("biz-o1", "acc-recon", new BigDecimal("10.00"), "CNY");
        paymentService.submit("biz-o2", "acc-recon", new BigDecimal("20.00"), "CNY");
        paymentService.submit("biz-o3", "acc-recon", new BigDecimal("30.00"), "CNY");

        // 对账单乱序：o3, o1, o2
        List<ReconItemResult> results = reconciliationService.reconcile("stmt-order-1", List.of(
                new ReconEntry("biz-o3", new BigDecimal("30.00"), "CNY"),
                new ReconEntry("biz-o1", new BigDecimal("10.00"), "CNY"),
                new ReconEntry("biz-o2", new BigDecimal("20.00"), "CNY")));

        assertEquals(3, results.size());
        assertTrue(results.stream().allMatch(r -> r.result() == ReconResult.MATCHED));
        assertEquals("biz-o3", results.get(0).bizNo(), "结论顺序与对账单条目一一对应");
        log.info("[业务:对账] 用例通过:乱序3条全部 MATCHED");
    }

    @Test
    @DisplayName("畸形与差异条目：字段缺失/金额不符/币种不符/指令不存在，结论各自可区分")
    void malformedAndMismatchedEntriesDistinguished() {
        log.info("[业务:对账] 用例:畸形与差异对账");
        paymentService.submit("biz-m1", "acc-recon", new BigDecimal("50.00"), "CNY");
        paymentService.submit("biz-m2", "acc-recon", new BigDecimal("60.00"), "CNY");

        List<ReconItemResult> results = reconciliationService.reconcile("stmt-bad-1", List.of(
                new ReconEntry(null, new BigDecimal("1.00"), "CNY"),          // 缺 bizNo
                new ReconEntry("biz-m1", null, "CNY"),                        // 缺 amount
                new ReconEntry("biz-m1", new BigDecimal("51.00"), "CNY"),     // 金额不符
                new ReconEntry("biz-m2", new BigDecimal("60.00"), "USD"),     // 币种不符
                new ReconEntry("biz-ghost", new BigDecimal("1.00"), "CNY"))); // 指令不存在

        assertEquals(ReconResult.MISSING_REQUIRED_FIELDS, results.get(0).result());
        assertEquals(ReconResult.MISSING_REQUIRED_FIELDS, results.get(1).result());
        assertEquals(ReconResult.AMOUNT_MISMATCH, results.get(2).result());
        assertEquals(ReconResult.CURRENCY_MISMATCH, results.get(3).result());
        assertEquals(ReconResult.PAYMENT_NOT_FOUND, results.get(4).result());

        // 金额/币种不符触发冲正，不默认以任何一方为准
        assertEquals(PaymentStatus.REVERSED,
                paymentRepository.findByIdempotencyKey("biz-m1").orElseThrow().getStatus());
        assertEquals(PaymentStatus.REVERSED,
                paymentRepository.findByIdempotencyKey("biz-m2").orElseThrow().getStatus());
        Account acc = accountRepository.findById("acc-recon").orElseThrow();
        assertEquals(0, new BigDecimal("1000.00").compareTo(acc.getBalance()), "冲正后冻结全部释放");
        assertEquals(0, BigDecimal.ZERO.compareTo(acc.getFrozen()));
        log.info("[业务:对账] 用例通过:5种结论可区分,差异条目已冲正, balance={}", acc.getBalance());
    }

    @Test
    @DisplayName("同一份对账单重复对账：复现首次结论，不重复记账、不重复冲正")
    void duplicateReconciliationIsReproducible() {
        log.info("[业务:对账] 用例:重复对账幂等复现");
        paymentService.submit("biz-d1", "acc-recon", new BigDecimal("70.00"), "CNY");
        List<ReconEntry> statement = List.of(new ReconEntry("biz-d1", new BigDecimal("71.00"), "CNY"));

        List<ReconItemResult> first = reconciliationService.reconcile("stmt-dup-1", statement);
        long recordsAfterFirst = reconRecordRepository.findByStatementId("stmt-dup-1").size();
        int ledgerAfterFirst = ledgerEntriesCount();

        List<ReconItemResult> second = reconciliationService.reconcile("stmt-dup-1", statement);

        assertEquals(first.get(0).result(), second.get(0).result(), "结论可复现");
        assertFalse(first.get(0).replayed());
        assertTrue(second.get(0).replayed(), "第二次为复现首次结论");
        assertEquals(recordsAfterFirst, reconRecordRepository.findByStatementId("stmt-dup-1").size(),
                "不重复落对账记录");
        assertEquals(ledgerAfterFirst, ledgerEntriesCount(), "不重复记账/不重复冲正");
        log.info("[业务:对账] 用例通过:重复对账结论复现且无重复副作用");
    }

    @Test
    @DisplayName("对账差异触发冲正只生效一次：同一支付被多份对账单判差异也不重复退款")
    void reconTriggeredReversalIsOnceOnly() {
        log.info("[业务:对账] 用例:跨对账单重复差异冲正");
        paymentService.submit("biz-x1", "acc-recon", new BigDecimal("80.00"), "CNY");
        reconciliationService.reconcile("stmt-x1", List.of(new ReconEntry("biz-x1", new BigDecimal("81.00"), "CNY")));
        BigDecimal balanceAfterFirst = accountRepository.findById("acc-recon").orElseThrow().getBalance();

        // 另一份对账单对同一支付判差异：指令已 REVERSED，冲正幂等跳过
        reconciliationService.reconcile("stmt-x2", List.of(new ReconEntry("biz-x1", new BigDecimal("82.00"), "CNY")));
        BigDecimal balanceAfterSecond = accountRepository.findById("acc-recon").orElseThrow().getBalance();

        assertEquals(0, balanceAfterFirst.compareTo(balanceAfterSecond), "冲正不得重复退款");
        assertEquals(0, new BigDecimal("1000.00").compareTo(balanceAfterSecond));
        log.info("[业务:对账] 用例通过:跨对账单差异冲正幂等, balance={}", balanceAfterSecond);
    }

    private int ledgerEntriesCount() {
        return ledgerRepositoryCount();
    }

    @Autowired
    private com.github.feng111627.paymentreconciliationledgerjava.repository.LedgerRepository ledgerRepository;

    private int ledgerRepositoryCount() {
        return ledgerRepository.findAll().size();
    }
}
