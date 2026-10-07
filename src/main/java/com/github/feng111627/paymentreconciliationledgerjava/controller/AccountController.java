package com.github.feng111627.paymentreconciliationledgerjava.controller;

import com.github.feng111627.paymentreconciliationledgerjava.domain.Account;
import com.github.feng111627.paymentreconciliationledgerjava.repository.AccountRepository;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {
    private final AccountRepository accountRepository;

    public AccountController(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    public record CreateAccountRequest(String accountId, String currency, BigDecimal initialBalance) {}

    @PostMapping
    public Account create(@RequestBody CreateAccountRequest request) {
        Account account = new Account(request.accountId(), request.currency());
        account.setBalance(request.initialBalance() == null ? BigDecimal.ZERO : request.initialBalance());
        return accountRepository.save(account);
    }

    @GetMapping("/{accountId}")
    public Account get(@PathVariable String accountId) {
        return accountRepository.findById(accountId).orElseThrow();
    }
}
