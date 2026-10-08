package com.github.feng111627.paymentreconciliationledgerjava.store;

import com.github.feng111627.paymentreconciliationledgerjava.domain.Payment;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/** 支付指令的本地内存存储。 */
@Repository
public class PaymentRepository {

    private final CopyOnWriteArrayList<Payment> payments = new CopyOnWriteArrayList<>();

    public Payment save(Payment payment) {
        payments.removeIf(p -> p.getBizKey().equals(payment.getBizKey()));
        payments.add(payment);
        return payment;
    }

    public Optional<Payment> findByBizKey(String bizKey) {
        return payments.stream().filter(p -> p.getBizKey().equals(bizKey)).findFirst();
    }

    public Optional<Payment> findById(String id) {
        return payments.stream().filter(p -> p.getId().equals(id)).findFirst();
    }

    public List<Payment> findAll() {
        return List.copyOf(payments);
    }
}
