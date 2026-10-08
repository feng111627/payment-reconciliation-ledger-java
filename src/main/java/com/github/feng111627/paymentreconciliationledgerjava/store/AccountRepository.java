package com.github.feng111627.paymentreconciliationledgerjava.store;

import com.github.feng111627.paymentreconciliationledgerjava.domain.Account;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 账户的本地内存存储（模拟数据库）。 */
@Repository
public class AccountRepository {

    private final Map<String, Account> accounts = new ConcurrentHashMap<>();

    public Account save(Account account) {
        accounts.put(account.getId(), account);
        return account;
    }

    public Optional<Account> findById(String id) {
        return Optional.ofNullable(accounts.get(id));
    }
}
