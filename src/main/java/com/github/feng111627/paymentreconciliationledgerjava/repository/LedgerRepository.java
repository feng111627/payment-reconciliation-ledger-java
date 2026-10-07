package com.github.feng111627.paymentreconciliationledgerjava.repository;

import com.github.feng111627.paymentreconciliationledgerjava.domain.LedgerEntry;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 本地内存账本流水存储（只追加）。 */
@Repository
public class LedgerRepository {
    private final CopyOnWriteArrayList<LedgerEntry> entries = new CopyOnWriteArrayList<>();

    public void append(LedgerEntry entry) {
        entries.add(entry);
    }

    public List<LedgerEntry> findByRefId(String refId) {
        return entries.stream().filter(e -> e.getRefId().equals(refId)).toList();
    }

    public List<LedgerEntry> findAll() {
        return List.copyOf(entries);
    }
}
