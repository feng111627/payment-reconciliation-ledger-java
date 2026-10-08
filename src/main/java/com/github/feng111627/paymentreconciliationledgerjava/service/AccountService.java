package com.github.feng111627.paymentreconciliationledgerjava.service;

import com.github.feng111627.paymentreconciliationledgerjava.domain.Account;
import com.github.feng111627.paymentreconciliationledgerjava.domain.CurrencyCode;
import com.github.feng111627.paymentreconciliationledgerjava.error.AccountNotFoundException;
import com.github.feng111627.paymentreconciliationledgerjava.error.PaymentValidationException;
import com.github.feng111627.paymentreconciliationledgerjava.store.AccountRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/** 账户服务：开户与查询。 */
@Service
public class AccountService {

    private final AccountRepository accountRepository;

    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    /** 开户；初始余额必须非负。 */
    public Account open(String id, String ownerName, CurrencyCode currency, BigDecimal initial) {
        if (id == null || id.isBlank()) {
            throw new PaymentValidationException("账户号不能为空", null);
        }
        if (currency == null) {
            throw new PaymentValidationException("币种不能为空", null);
        }
        if (initial == null || initial.signum() < 0) {
            throw new PaymentValidationException("初始余额不能为负: " + initial, null);
        }
        return accountRepository.save(
                new Account(id, ownerName, currency, initial, BigDecimal.ZERO, BigDecimal.ZERO));
    }

    public Account require(String id) {
        return accountRepository.findById(id)
                .orElseThrow(() -> new AccountNotFoundException("账户不存在: " + id));
    }
}
