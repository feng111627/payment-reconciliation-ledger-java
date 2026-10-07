package com.github.feng111627.paymentreconciliationledgerjava.repository;

import com.github.feng111627.paymentreconciliationledgerjava.domain.PaymentInstruction;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 本地内存支付指令存储，按幂等键索引。 */
@Repository
public class PaymentRepository {
    private final ConcurrentHashMap<String, PaymentInstruction> byIdempotencyKey = new ConcurrentHashMap<>();

    public Optional<PaymentInstruction> findByIdempotencyKey(String key) {
        return Optional.ofNullable(byIdempotencyKey.get(key));
    }

    /** 原子写入：仅当键不存在时放入，返回已存在的指令或 null（表示写入成功）。 */
    public PaymentInstruction putIfAbsent(PaymentInstruction instruction) {
        return byIdempotencyKey.putIfAbsent(instruction.getIdempotencyKey(), instruction);
    }
}
