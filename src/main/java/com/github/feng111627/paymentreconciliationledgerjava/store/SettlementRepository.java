package com.github.feng111627.paymentreconciliationledgerjava.store;

import com.github.feng111627.paymentreconciliationledgerjava.domain.SettlementBatch;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 批量结算请求的幂等记录存储（按 batchId）。 */
@Repository
public class SettlementRepository {

    private final ConcurrentMap<String, SettlementBatch> batches = new ConcurrentHashMap<>();

    /** 不存在则放入并返回 null；已存在返回已有批次，保证批次不重复执行。 */
    public SettlementBatch saveIfAbsent(SettlementBatch batch) {
        return batches.putIfAbsent(batch.getBatchId(), batch);
    }

    public Optional<SettlementBatch> findById(String batchId) {
        return Optional.ofNullable(batches.get(batchId));
    }
}
