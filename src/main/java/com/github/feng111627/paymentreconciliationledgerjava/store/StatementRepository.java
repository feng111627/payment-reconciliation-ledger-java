package com.github.feng111627.paymentreconciliationledgerjava.store;

import com.github.feng111627.paymentreconciliationledgerjava.domain.ReconciliationRecord;
import com.github.feng111627.paymentreconciliationledgerjava.domain.StatementBatch;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** 对账单与对账结论的本地内存存储。 */
@Repository
public class StatementRepository {

    private final ConcurrentMap<String, StatementBatch> batches = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<ReconciliationRecord> records = new CopyOnWriteArrayList<>();

    public StatementBatch saveBatch(StatementBatch batch) {
        batches.put(batch.getBatchId(), batch);
        return batch;
    }

    public Optional<StatementBatch> findBatch(String batchId) {
        return Optional.ofNullable(batches.get(batchId));
    }

    public ReconciliationRecord appendRecord(ReconciliationRecord record) {
        records.add(record);
        return record;
    }

    public List<ReconciliationRecord> findRecordsByBatch(String batchId) {
        return records.stream().filter(r -> r.getBatchId().equals(batchId)).toList();
    }

    public boolean existsRecord(String batchId, String bizKey) {
        return records.stream()
                .anyMatch(r -> r.getBatchId().equals(batchId) && r.getBizKey().equals(bizKey));
    }
}
