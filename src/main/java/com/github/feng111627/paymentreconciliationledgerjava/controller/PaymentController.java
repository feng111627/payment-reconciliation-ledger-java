package com.github.feng111627.paymentreconciliationledgerjava.controller;

import com.github.feng111627.paymentreconciliationledgerjava.service.PaymentService;
import com.github.feng111627.paymentreconciliationledgerjava.service.SubmitResult;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {
    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    public record SubmitRequest(String idempotencyKey, String accountId, BigDecimal amount, String currency) {}
    public record SettleRequest(List<String> idempotencyKeys) {}

    @PostMapping
    public SubmitResult submit(@RequestBody SubmitRequest request) {
        return paymentService.submit(request.idempotencyKey(), request.accountId(),
                request.amount(), request.currency());
    }

    @PostMapping("/settle")
    public Object settle(@RequestBody SettleRequest request) {
        return paymentService.settleBatch(request.idempotencyKeys());
    }

    @PostMapping("/{idempotencyKey}/reverse")
    public Object reverse(@PathVariable String idempotencyKey, @RequestBody Map<String, String> body) {
        return paymentService.reverse(idempotencyKey, body.getOrDefault("reason", "manual"));
    }
}
