package com.github.feng111627.paymentreconciliationledgerjava.web;

import com.github.feng111627.paymentreconciliationledgerjava.domain.CurrencyCode;
import com.github.feng111627.paymentreconciliationledgerjava.service.AccountService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** 启动时准备演示账户。 */
@Component
public class DemoDataInitializer implements CommandLineRunner {

    private final AccountService accountService;

    public DemoDataInitializer(AccountService accountService) {
        this.accountService = accountService;
    }

    @Override
    public void run(String... args) {
        accountService.open("ACC-CNY-001", "演示人民币账户", CurrencyCode.CNY, new BigDecimal("1000.00"));
        accountService.open("ACC-USD-001", "演示美元账户", CurrencyCode.USD, new BigDecimal("1000.00"));
    }
}
