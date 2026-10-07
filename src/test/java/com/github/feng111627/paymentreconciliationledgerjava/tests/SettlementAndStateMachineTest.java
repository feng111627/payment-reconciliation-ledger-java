package com.github.feng111627.paymentreconciliationledgerjava.tests;

import com.github.feng111627.paymentreconciliationledgerjava.domain.Account;
import com.github.feng111627.paymentreconciliationledgerjava.domain.IllegalTransitionException;
import com.github.feng111627.paymentreconciliationledgerjava.domain.PaymentInstruction;
import com.github.feng111627.paymentreconciliationledgerjava.domain.PaymentStatus;
import com.github.feng111627.paymentreconciliationledgerjava.repository.AccountRepository;
import com.github.feng111627.paymentreconciliationledgerjava.repository.LedgerRepository;
import com.github.feng111627.paymentreconciliationledgerjava.service.PaymentService;
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

/** 业务覆盖：批量结算 / 部分结算 / 冲正 的账本守恒与状态机流转。 */
@SpringBootTest
class SettlementAndStateMachineTest {
    private static final Logger log = LoggerFactory.getLogger(SettlementAndStateMachineTest.class);

    @Autowired PaymentService paymentService;
    @Autowired AccountRepository accountRepository;
    @Autowired LedgerRepository ledgerRepository;

    @BeforeEach
    void setUp() {
        Account acc = new Account("acc-settle", "CNY");
        acc.setBalance(new BigDecimal("1000.00"));
        accountRepository.save(acc);
        log.info("[业务:结算与状态机] 初始化账户 acc-settle 余额 1000.00 CNY");
    }

    @Test
    @DisplayName("批量全额结算：冻结出账并收取一次手续费，账本守恒")
    void batchSettleConservesLedger() {
        log.info("[业务:结算与状态机] 用例:批量全额结算+手续费");
        paymentService.submit("biz-s1", "acc-settle", new BigDecimal("100.00"), "CNY");
        paymentService.submit("biz-s2", "acc-settle", new BigDecimal("200.00"), "CNY");

        List<PaymentInstruction> settled = paymentService.settleBatch(List.of("biz-s1", "biz-s2"));
        assertEquals(2, settled.size());
        assertTrue(settled.stream().allMatch(p -> p.getStatus() == PaymentStatus.SETTLED));

        Account acc = accountRepository.findById("acc-settle").orElseThrow();
        // 1000 - 300(结算出账) - 0.30(手续费 0.1%)
        assertEquals(0, new BigDecimal("699.70").compareTo(acc.getBalance()));
        assertEquals(0, BigDecimal.ZERO.compareTo(acc.getFrozen()));
        assertEquals(0, BigDecimal.ZERO.compareTo(acc.getInTransit()));
        assertEquals(0, new BigDecimal("0.30").compareTo(acc.getFeeTotal()));
        log.info("[业务:结算与状态机] 用例通过:balance={} fee={}", acc.getBalance(), acc.getFeeTotal());
    }

    @Test
    @DisplayName("部分结算：PARTIALLY_SETTLED 中间态，补足后 SETTLED，手续费只收一次")
    void partialSettlement() {
        log.info("[业务:结算与状态机] 用例:部分结算->补足结算");
        paymentService.submit("biz-p1", "acc-settle", new BigDecimal("100.00"), "CNY");

        PaymentInstruction p1 = paymentService.settle("biz-p1", new BigDecimal("40.00"));
        assertEquals(PaymentStatus.PARTIALLY_SETTLED, p1.getStatus());
        Account mid = accountRepository.findById("acc-settle").orElseThrow();
        assertEquals(0, new BigDecimal("60.00").compareTo(mid.getFrozen()));
        assertEquals(0, BigDecimal.ZERO.compareTo(mid.getFeeTotal()), "未结清不收手续费");

        PaymentInstruction p2 = paymentService.settle("biz-p1", new BigDecimal("60.00"));
        assertEquals(PaymentStatus.SETTLED, p2.getStatus());
        Account done = accountRepository.findById("acc-settle").orElseThrow();
        assertEquals(0, BigDecimal.ZERO.compareTo(done.getFrozen()));
        assertEquals(0, new BigDecimal("0.10").compareTo(done.getFeeTotal()), "手续费只收一次");
        log.info("[业务:结算与状态机] 用例通过:部分结算守恒, fee={}", done.getFeeTotal());
    }

    @Test
    @DisplayName("冲正：未结算部分解冻、已结算部分退回、手续费退还，且只能冲正一次")
    void reversalIsExactAndOnceOnly() {
        log.info("[业务:结算与状态机] 用例:部分结算后冲正");
        paymentService.submit("biz-r1", "acc-settle", new BigDecimal("100.00"), "CNY");
        paymentService.settle("biz-r1", new BigDecimal("40.00"));

        PaymentInstruction reversed = paymentService.reverse("biz-r1", "test");
        assertEquals(PaymentStatus.REVERSED, reversed.getStatus());
        Account acc = accountRepository.findById("acc-settle").orElseThrow();
        // 60 解冻 + 40 冲正入账 = 回到 1000，无重复扣款无漏扣
        assertEquals(0, new BigDecimal("1000.00").compareTo(acc.getBalance()));
        assertEquals(0, BigDecimal.ZERO.compareTo(acc.getFrozen()));
        assertEquals(0, BigDecimal.ZERO.compareTo(acc.getInTransit()));

        // 全额结算后冲正：退本金+退手续费
        paymentService.submit("biz-r2", "acc-settle", new BigDecimal("100.00"), "CNY");
        paymentService.settle("biz-r2", null);
        paymentService.reverse("biz-r2", "test");
        Account acc2 = accountRepository.findById("acc-settle").orElseThrow();
        assertEquals(0, new BigDecimal("1000.00").compareTo(acc2.getBalance()), "全额冲正后资金与手续费全部回滚");
        assertEquals(0, BigDecimal.ZERO.compareTo(acc2.getFeeTotal()));
        log.info("[业务:结算与状态机] 用例通过:冲正后账户完全还原 balance={}", acc2.getBalance());
    }

    @Test
    @DisplayName("非法状态流转被明确拒绝：重复冲正、冲正后结算、超额部分结算")
    void illegalTransitionsRejected() {
        log.info("[业务:结算与状态机] 用例:非法状态流转");
        paymentService.submit("biz-t1", "acc-settle", new BigDecimal("100.00"), "CNY");
        paymentService.reverse("biz-t1", "test");
        // REVERSED -> REVERSED 非法
        assertThrows(IllegalTransitionException.class, () -> paymentService.reverse("biz-t1", "again"));
        // REVERSED -> SETTLED 非法
        assertThrows(IllegalTransitionException.class, () -> paymentService.settle("biz-t1", null));

        paymentService.submit("biz-t2", "acc-settle", new BigDecimal("100.00"), "CNY");
        paymentService.settle("biz-t2", null);
        // SETTLED -> SETTLED 非法（不能重复结算扣款）
        assertThrows(IllegalTransitionException.class, () -> paymentService.settle("biz-t2", null));

        Account acc = accountRepository.findById("acc-settle").orElseThrow();
        // biz-t1 冲正还原 1000；biz-t2 正常结算 100 + 手续费 0.10；非法流转不得再改动任何口径
        assertEquals(0, new BigDecimal("899.90").compareTo(acc.getBalance()),
                "非法流转被拒绝后账户口径不变");
        assertEquals(0, BigDecimal.ZERO.compareTo(acc.getFrozen()));
        assertEquals(0, new BigDecimal("0.10").compareTo(acc.getFeeTotal()));
        log.info("[业务:结算与状态机] 用例通过:非法流转均被拒绝且账户守恒");
    }
}
