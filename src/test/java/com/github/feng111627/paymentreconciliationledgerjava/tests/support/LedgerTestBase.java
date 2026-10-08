package com.github.feng111627.paymentreconciliationledgerjava.tests.support;

import com.github.feng111627.paymentreconciliationledgerjava.domain.CurrencyCode;
import com.github.feng111627.paymentreconciliationledgerjava.service.AccountService;
import com.github.feng111627.paymentreconciliationledgerjava.service.LedgerService;
import com.github.feng111627.paymentreconciliationledgerjava.service.PaymentService;
import com.github.feng111627.paymentreconciliationledgerjava.service.ReconciliationService;
import com.github.feng111627.paymentreconciliationledgerjava.service.SettlementService;
import com.github.feng111627.paymentreconciliationledgerjava.store.*;
import org.junit.jupiter.api.BeforeEach;

import java.math.BigDecimal;

/**
 * 纯单元测试基类：手动装配全新的内存仓库与服务，避免 Spring 上下文与端口依赖；
 * 每个用例独立数据。
 */
public abstract class LedgerTestBase {

    protected AccountRepository accountRepository;
    protected PaymentRepository paymentRepository;
    protected LedgerRepository ledgerRepository;
    protected IdempotencyRepository idempotencyRepository;
    protected StatementRepository statementRepository;
    protected SettlementRepository settlementRepository;

    protected AccountService accountService;
    protected LedgerService ledgerService;
    protected PaymentService paymentService;
    protected SettlementService settlementService;
    protected ReconciliationService reconciliationService;

    @BeforeEach
    void setUp() {
        accountRepository = new AccountRepository();
        paymentRepository = new PaymentRepository();
        ledgerRepository = new LedgerRepository();
        idempotencyRepository = new IdempotencyRepository();
        statementRepository = new StatementRepository();
        settlementRepository = new SettlementRepository();

        ledgerService = new LedgerService(accountRepository, ledgerRepository);
        accountService = new AccountService(accountRepository);
        paymentService = new PaymentService(paymentRepository, idempotencyRepository,
                ledgerService, accountRepository);
        settlementService = new SettlementService(settlementRepository, paymentRepository,
                ledgerService, paymentService);
        reconciliationService = new ReconciliationService(statementRepository,
                paymentRepository, paymentService, ledgerService);

        seed();
    }

    protected void seed() {
        accountService.open("ACC-CNY", "人民币账户", CurrencyCode.CNY, new BigDecimal("1000.00"));
        accountService.open("ACC-USD", "美元账户", CurrencyCode.USD, new BigDecimal("1000.00"));
        accountService.open("ACC-POOR", "低余额账户", CurrencyCode.CNY, new BigDecimal("10.00"));
    }
}
