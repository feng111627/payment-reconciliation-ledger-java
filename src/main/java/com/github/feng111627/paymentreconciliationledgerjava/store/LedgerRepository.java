package com.github.feng111627.paymentreconciliationledgerjava.store;

import com.github.feng111627.paymentreconciliationledgerjava.domain.LedgerEntry;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/** 账本流水的本地内存存储，只追加，不修改不删除。 */
@Repository
public class LedgerRepository {

    private final CopyOnWriteArrayList<LedgerEntry> entries = new CopyOnWriteArrayList<>();
    private final AtomicLong sequence = new AtomicLong();

    public LedgerEntry append(LedgerEntry entry) {
        entries.add(entry);
        return entry;
    }

    public List<LedgerEntry> findByPaymentId(String paymentId) {
        return entries.stream().filter(e -> e.getPaymentId().equals(paymentId)).toList();
    }

    public List<LedgerEntry> findAll() {
        return List.copyOf(entries);
    }

    public String nextId() {
        return "LE-" + sequence.incrementAndGet();
    }
}
