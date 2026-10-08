package com.github.feng111627.paymentreconciliationledgerjava.store;

import com.github.feng111627.paymentreconciliationledgerjava.domain.IdempotentRecord;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 幂等记录存储：putIfAbsent 语义保证同一 bizKey 只有一条首次结果（并发安全）。 */
@Repository
public class IdempotencyRepository {

    private final ConcurrentMap<String, IdempotentRecord> records = new ConcurrentHashMap<>();

    /** 不存在则放入并返回 null；已存在则不覆盖并返回已有记录（并发重复提交只会有一个胜出）。 */
    public IdempotentRecord putIfAbsent(String bizKey, IdempotentRecord record) {
        return records.putIfAbsent(bizKey, record);
    }

    public Optional<IdempotentRecord> findByBizKey(String bizKey) {
        return Optional.ofNullable(records.get(bizKey));
    }
}
