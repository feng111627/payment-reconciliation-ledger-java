package com.github.feng111627.paymentreconciliationledgerjava.tests;

import com.github.feng111627.paymentreconciliationledgerjava.domain.Account;
import com.github.feng111627.paymentreconciliationledgerjava.domain.PaymentRejectedException;
import com.github.feng111627.paymentreconciliationledgerjava.domain.PaymentStatus;
import com.github.feng111627.paymentreconciliationledgerjava.domain.RejectReason;
import com.github.feng111627.paymentreconciliationledgerjava.repository.AccountRepository;
import com.github.feng111627.paymentreconciliationledgerjava.repository.LedgerRepository;
import com.github.feng111627.paymentreconciliationledgerjava.service.PaymentService;
import com.github.feng111627.paymentreconciliationledgerjava.service.SubmitResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/** 业务覆盖：支付指令受理的幂等性（重复提交 / 并发重复提交）与可区分的拒绝原因。 */
@SpringBootTest
class PaymentIdempotencyTest {
    private static final Logger log = LoggerFactory.getLogger(PaymentIdempotencyTest.class);

    @Autowired PaymentService paymentService;
    @Autowired AccountRepository accountRepository;
    @Autowired LedgerRepository ledgerRepository;

    @BeforeEach
    void setUp() {
        Account acc = new Account("acc-idem", "CNY");
        acc.setBalance(new BigDecimal("1000.00"));
        accountRepository.save(acc);
        log.info("[业务:支付受理-幂等] 初始化账户 acc-idem 余额 1000.00 CNY");
    }

    @Test
    @DisplayName("同一幂等键重复提交只生效一次，返回首次受理结果")
    void duplicateSubmitIsIdempotent() {
        log.info("[业务:支付受理-幂等] 用例:同一幂等键串行重复提交");
        SubmitResult first = paymentService.submit("biz-dup-1", "acc-idem", new BigDecimal("100.00"), "CNY");
        SubmitResult second = paymentService.submit("biz-dup-1", "acc-idem", new BigDecimal("100.00"), "CNY");

        assertFalse(first.duplicate());
        assertTrue(second.duplicate());
        assertEquals(first.status(), second.status());
        // 只冻结一次
        Account acc = accountRepository.findById("acc-idem").orElseThrow();
        assertEquals(0, new BigDecimal("900.00").compareTo(acc.getBalance()));
        assertEquals(0, new BigDecimal("100.00").compareTo(acc.getFrozen()));
        assertEquals(1, ledgerRepository.findByRefId("biz-dup-1").size());
        log.info("[业务:支付受理-幂等] 用例通过:重复提交未重复冻结, frozen={}", acc.getFrozen());
    }

    @Test
    @DisplayName("并发重复提交同一幂等键：只有一次受理，只冻结一次")
    void concurrentDuplicateSubmit() throws Exception {
        log.info("[业务:支付受理-幂等] 用例:并发重复提交(16线程)");
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<SubmitResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return paymentService.submit("biz-concurrent-1", "acc-idem", new BigDecimal("50.00"), "CNY");
            }));
        }
        ready.await();
        go.countDown();
        int accepted = 0, duplicated = 0;
        for (Future<SubmitResult> f : futures) {
            if (f.get().duplicate()) duplicated++; else accepted++;
        }
        pool.shutdown();

        assertEquals(1, accepted, "并发下只能有一个请求被受理");
        assertEquals(threads - 1, duplicated);
        Account acc = accountRepository.findById("acc-idem").orElseThrow();
        assertEquals(0, new BigDecimal("50.00").compareTo(acc.getFrozen()), "并发重复提交不得重复扣款/冻结");
        assertEquals(0, new BigDecimal("950.00").compareTo(acc.getBalance()));
        log.info("[业务:支付受理-幂等] 用例通过:accepted=1 duplicated={} frozen={}", duplicated, acc.getFrozen());
    }

    @Test
    @DisplayName("余额不足被拒绝，原因可区分且不产生任何冻结")
    void insufficientBalanceRejected() {
        log.info("[业务:支付受理-拒绝原因] 用例:余额不足");
        PaymentRejectedException ex = assertThrows(PaymentRejectedException.class,
                () -> paymentService.submit("biz-poor-1", "acc-idem", new BigDecimal("99999.00"), "CNY"));
        assertEquals(RejectReason.INSUFFICIENT_BALANCE, ex.getReason());
        Account acc = accountRepository.findById("acc-idem").orElseThrow();
        assertEquals(0, BigDecimal.ZERO.compareTo(acc.getFrozen()));
        log.info("[业务:支付受理-拒绝原因] 用例通过:reason={}", ex.getReason());
    }

    @Test
    @DisplayName("币种不符 / 金额非法 / 账户不存在分别给出可区分原因")
    void distinguishableRejectReasons() {
        log.info("[业务:支付受理-拒绝原因] 用例:币种不符/金额非法/账户不存在");
        assertEquals(RejectReason.CURRENCY_MISMATCH, assertThrows(PaymentRejectedException.class,
                () -> paymentService.submit("biz-cur-1", "acc-idem", new BigDecimal("10.00"), "USD")).getReason());
        assertEquals(RejectReason.INVALID_AMOUNT, assertThrows(PaymentRejectedException.class,
                () -> paymentService.submit("biz-amt-1", "acc-idem", new BigDecimal("-1.00"), "CNY")).getReason());
        assertEquals(RejectReason.INVALID_AMOUNT, assertThrows(PaymentRejectedException.class,
                () -> paymentService.submit("biz-amt-2", "acc-idem", null, "CNY")).getReason());
        assertEquals(RejectReason.ACCOUNT_NOT_FOUND, assertThrows(PaymentRejectedException.class,
                () -> paymentService.submit("biz-acc-1", "no-such-acc", new BigDecimal("10.00"), "CNY")).getReason());
        // 被拒绝的提交不占用幂等键语义之外的任何资金
        Account acc = accountRepository.findById("acc-idem").orElseThrow();
        assertEquals(0, BigDecimal.ZERO.compareTo(acc.getFrozen()));
        log.info("[业务:支付受理-拒绝原因] 用例通过:四种原因均可区分,账户无残留冻结");
    }
}
