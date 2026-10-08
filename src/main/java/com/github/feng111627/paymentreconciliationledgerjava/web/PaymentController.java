package com.github.feng111627.paymentreconciliationledgerjava.web;

import com.github.feng111627.paymentreconciliationledgerjava.dto.AcceptRequest;
import com.github.feng111627.paymentreconciliationledgerjava.dto.AcceptResult;
import com.github.feng111627.paymentreconciliationledgerjava.service.PaymentService;
import org.springframework.web.bind.annotation.*;

/** 支付指令入口：POST /api/payments。 */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    public AcceptResult accept(@RequestBody AcceptRequest request) {
        return paymentService.accept(request);
    }
}
