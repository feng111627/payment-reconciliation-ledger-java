package com.github.feng111627.paymentreconciliationledgerjava.web;

import com.github.feng111627.paymentreconciliationledgerjava.domain.Account;
import com.github.feng111627.paymentreconciliationledgerjava.domain.LedgerEntry;
import com.github.feng111627.paymentreconciliationledgerjava.domain.Payment;
import com.github.feng111627.paymentreconciliationledgerjava.error.AccountNotFoundException;
import com.github.feng111627.paymentreconciliationledgerjava.error.BusinessException;
import com.github.feng111627.paymentreconciliationledgerjava.service.LedgerService;
import com.github.feng111627.paymentreconciliationledgerjava.store.AccountRepository;
import com.github.feng111627.paymentreconciliationledgerjava.store.PaymentRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 只读查询入口：账户口径、支付指令、账本流水（便于人工核对账本守恒）。 */
@RestController
@RequestMapping("/api")
public class QueryController {

    private final AccountRepository accountRepository;
    private final PaymentRepository paymentRepository;
    private final LedgerService ledgerService;

    public QueryController(AccountRepository accountRepository,
                          PaymentRepository paymentRepository,
                          LedgerService ledgerService) {
        this.accountRepository = accountRepository;
        this.paymentRepository = paymentRepository;
        this.ledgerService = ledgerService;
    }

    @GetMapping("/accounts/{id}")
    public Map<String, Object> account(@PathVariable String id) {
        Account account = accountRepository.findById(id)
                .orElseThrow(() -> new AccountNotFoundException("账户不存在: " + id));
        return Map.of(
                "id", account.getId(),
                "ownerName", account.getOwnerName(),
                "currency", account.getCurrency(),
                "available", account.getAvailable(),
                "frozen", account.getFrozen(),
                "inflight", account.getInflight(),
                "total", account.getTotal());
    }

    @GetMapping("/payments/{bizKey}")
    public Payment payment(@PathVariable String bizKey) {
        return paymentRepository.findByBizKey(bizKey)
                .orElseThrow(() -> new BusinessException("支付指令不存在: " + bizKey));
    }

    @GetMapping("/payments")
    public List<Payment> payments() {
        return paymentRepository.findAll();
    }

    @GetMapping("/ledger")
    public List<LedgerEntry> ledger() {
        return ledgerService.allEntries();
    }
}
