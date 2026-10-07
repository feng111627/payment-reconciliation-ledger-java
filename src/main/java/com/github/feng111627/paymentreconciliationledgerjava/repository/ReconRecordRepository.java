package com.github.feng111627.paymentreconciliationledgerjava.repository;

import com.github.feng111627.paymentreconciliationledgerjava.domain.ReconRecord;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 对账结论存储：键为 statementId + bizNo，保证重复对账可复现。 */
@Repository
public class ReconRecordRepository {
    private final ConcurrentHashMap<String, ReconRecord> records = new ConcurrentHashMap<>();

    private static String key(String statementId, String bizNo) {
        return statementId + "::" + bizNo;
    }

    public Optional<ReconRecord> find(String statementId, String bizNo) {
        return Optional.ofNullable(records.get(key(statementId, bizNo)));
    }

    public ReconRecord putIfAbsent(ReconRecord record) {
        return records.putIfAbsent(key(record.getStatementId(), record.getBizNo()), record);
    }

    public List<ReconRecord> findByStatementId(String statementId) {
        return records.values().stream()
                .filter(r -> r.getStatementId().equals(statementId))
                .toList();
    }
}
