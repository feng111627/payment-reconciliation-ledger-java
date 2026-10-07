package com.github.feng111627.paymentreconciliationledgerjava.repository;

import com.github.feng111627.paymentreconciliationledgerjava.domain.Account;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 本地内存账户存储。 */
@Repository
public class AccountRepository {
    private final ConcurrentHashMap<String, Account> accounts = new ConcurrentHashMap<>();

    public Optional<Account> findById(String accountId) {
        return Optional.ofNullable(accounts.get(accountId));
    }

    public Account save(Account account) {
        accounts.put(account.getAccountId(), account);
        return account;
    }
}
