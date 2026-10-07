package com.github.feng111627.paymentreconciliationledgerjava.tests;

import com.github.feng111627.paymentreconciliationledgerjava.domain.Account;
import com.github.feng111627.paymentreconciliationledgerjava.domain.LedgerEntry;
import com.github.feng111627.paymentreconciliationledgerjava.domain.LedgerEntryType;
import com.github.feng111627.paymentreconciliationledgerjava.domain.ReconEntry;
import com.github.feng111627.paymentreconciliationledgerjava.repository.AccountRepository;
import com.github.feng111627.paymentreconciliationledgerjava.repository.LedgerRepository;
import com.github.feng111627.paymentreconciliationledgerjava.service.PaymentService;
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
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** 业务覆盖：账本守恒——提交/结算/对账冲正多入口混合后，账户口径与流水严格对得上。 */
@SpringBootTest
class LedgerConservationTest {
    private static final Logger log = LoggerFactory.getLogger(LedgerConservationTest.class);
    private static final BigDecimal INITIAL = new BigDecimal("1000.00");

    @Autowired PaymentService paymentService;
    @Autowired ReconciliationService reconciliationService;
    @Autowired AccountRepository accountRepository;
    @Autowired LedgerRepository ledgerRepository;

    @BeforeEach
    void setUp() {
        Account acc = new Account("acc-ledger", "CNY");
        acc.setBalance(INITIAL);
        accountRepository.save(acc);
        log.info("[业务:账本守恒] 初始化账户 acc-ledger 余额 {} CNY", INITIAL);
    }

    @Test
    @DisplayName("混合入口（提交/部分结算/全额结算/对账冲正/手工冲正）后账本严格守恒")
    void mixedEntriesConserveLedger() {
        log.info("[业务:账本守恒] 用例:多入口混合后守恒校验");
        // 1. 提交三笔
        paymentService.submit("biz-l1", "acc-ledger", new BigDecimal("100.00"), "CNY");
        paymentService.submit("biz-l2", "acc-ledger", new BigDecimal("200.00"), "CNY");
        paymentService.submit("biz-l3", "acc-ledger", new BigDecimal("50.00"), "CNY");
        // 2. l1 全额结算；l2 部分结算 80
        paymentService.settleBatch(List.of("biz-l1"));
        paymentService.settle("biz-l2", new BigDecimal("80.00"));
        // 3. 对账判 l2 金额不符 -> 触发冲正（已结算 80 退回 + 未结算 120 解冻）
        reconciliationService.reconcile("stmt-l1",
                List.of(new ReconEntry("biz-l2", new BigDecimal("201.00"), "CNY")));
        // 4. l3 手工冲正
        paymentService.reverse("biz-l3", "manual");

        Account acc = accountRepository.findById("acc-ledger").orElseThrow();
        // 期望：1000 - 100(l1出账) - 0.10(l1手续费) = 899.90；l2/l3 全部还原
        assertEquals(0, new BigDecimal("899.90").compareTo(acc.getBalance()));
        assertEquals(0, BigDecimal.ZERO.compareTo(acc.getFrozen()));
        assertEquals(0, BigDecimal.ZERO.compareTo(acc.getInTransit()));
        assertEquals(0, new BigDecimal("0.10").compareTo(acc.getFeeTotal()));

        // 守恒校验：每笔指令的流水净额与账户口径一致
        assertConservation("biz-l1");
        assertConservation("biz-l2");
        assertConservation("biz-l3");
        log.info("[业务:账本守恒] 用例通过:balance={} frozen={} inTransit={} fee={}",
                acc.getBalance(), acc.getFrozen(), acc.getInTransit(), acc.getFeeTotal());
    }

    /** 单指令维度：冻结流入总额 == 解冻 + 出账；冲正退回不超过出账。 */
    private void assertConservation(String bizNo) {
        List<LedgerEntry> entries = ledgerRepository.findByRefId(bizNo);
        assertFalse(entries.isEmpty(), bizNo + " 必须有流水");
        Map<LedgerEntryType, BigDecimal> byType = entries.stream().collect(Collectors.groupingBy(
                LedgerEntry::getType,
                Collectors.mapping(LedgerEntry::getAmount,
                        Collectors.reducing(BigDecimal.ZERO, BigDecimal::add))));
        // 冻结流入（正向 FREEZE）与冻结流出（负向 FREEZE，转入在途）
        BigDecimal freezeIn = entries.stream()
                .filter(e -> e.getType() == LedgerEntryType.FREEZE && e.getAmount().signum() > 0)
                .map(LedgerEntry::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal unfrozen = byType.getOrDefault(LedgerEntryType.UNFREEZE, BigDecimal.ZERO);
        BigDecimal debited = byType.getOrDefault(LedgerEntryType.DEBIT, BigDecimal.ZERO);
        BigDecimal reversed = byType.getOrDefault(LedgerEntryType.REVERSAL_CREDIT, BigDecimal.ZERO);
        // 冻结流入 == 解冻回来 + 出账离开（冻结的钱只有这两条去路）
        assertEquals(0, freezeIn.compareTo(unfrozen.add(debited)),
                bizNo + " 冻结流入必须等于解冻+出账: " + byType);
        assertTrue(reversed.compareTo(debited) <= 0, bizNo + " 冲正不得超过出账");
        log.info("[业务:账本守恒] {} 守恒: freezeIn={} {}", bizNo, freezeIn, byType);
    }
}
