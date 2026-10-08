package com.github.feng111627.paymentreconciliationledgerjava.tests;

import com.github.feng111627.paymentreconciliationledgerjava.domain.Account;
import com.github.feng111627.paymentreconciliationledgerjava.dto.AcceptRequest;
import com.github.feng111627.paymentreconciliationledgerjava.dto.AcceptResult;
import com.github.feng111627.paymentreconciliationledgerjava.tests.support.BusinessScenario;
import com.github.feng111627.paymentreconciliationledgerjava.tests.support.LedgerTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@BusinessScenario("并发重复提交")
class ConcurrentIdempotencyTest extends LedgerTestBase {

    @Test
    @DisplayName("32 个线程用同一业务号并发提交：只有一笔生效，其余全部重放，仅扣款一次")
    void concurrentDuplicatesChargeOnce() throws Exception {
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<AcceptResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<AcceptResult> task = () -> paymentService.accept(
                    new AcceptRequest("BIZ-CONC-1", "ACC-CNY",
                            new BigDecimal("100.00"), "CNY", new BigDecimal("1.00")));
            futures.add(pool.submit(task));
        }
        List<AcceptResult> results = new ArrayList<>();
        for (Future<AcceptResult> f : futures) {
            results.add(f.get(10, TimeUnit.SECONDS));
        }
        pool.shutdown();

        long acceptedFirst = results.stream().filter(r -> r.accepted() && !r.replayed()).count();
        long replayed = results.stream().filter(AcceptResult::replayed).count();
        assertEquals(1, acceptedFirst, "并发重复提交中首次生效的只能有 1 笔");
        assertEquals(threads - 1, replayed, "其余提交必须全部命中幂等重放");

        // 资金只冻结一次：本金 100 + 手续费 1
        Account account = accountService.require("ACC-CNY");
        assertEquals(new BigDecimal("899.00"), account.getAvailable());
        assertEquals(new BigDecimal("101.00"), account.getFrozen());

        // 指令与流水都只有一笔
        assertEquals(1, paymentRepository.findAll().size());
        assertEquals(1, ledgerRepository.findAll().size());
    }

    @Test
    @DisplayName("并发场景下余额不足的请求：绝不允许两笔同时穿透冻结")
    void concurrentRequestsCannotOverdraft() throws Exception {
        // 账户只有 10.00，每笔要冻结 8.00：最多成功 1 笔
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<AcceptResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> paymentService.accept(
                    new AcceptRequest("BIZ-CONC-RACE-" + idx, "ACC-POOR",
                            new BigDecimal("8.00"), "CNY", BigDecimal.ZERO))));
        }
        int success = 0;
        int rejected = 0;
        for (Future<AcceptResult> f : futures) {
            AcceptResult r = f.get(10, TimeUnit.SECONDS);
            if (r.accepted()) {
                success++;
            } else {
                rejected++;
                assertEquals(com.github.feng111627.paymentreconciliationledgerjava.domain.RejectReason
                        .INSUFFICIENT_BALANCE, r.rejectReason());
            }
        }
        pool.shutdown();

        assertEquals(1, success, "10 元账户、每笔 8 元，只可能成功 1 笔");
        assertEquals(threads - 1, rejected);
        Account account = accountService.require("ACC-POOR");
        assertEquals(new BigDecimal("2.00"), account.getAvailable());
        assertEquals(new BigDecimal("8.00"), account.getFrozen());
        assertTrue(account.getAvailable().add(account.getFrozen())
                .add(account.getInflight()).compareTo(new BigDecimal("10.00")) == 0,
                "账本总额守恒");
    }
}
