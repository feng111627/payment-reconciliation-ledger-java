package com.github.feng111627.paymentreconciliationledgerjava.controller;

import com.github.feng111627.paymentreconciliationledgerjava.domain.ReconEntry;
import com.github.feng111627.paymentreconciliationledgerjava.service.ReconItemResult;
import com.github.feng111627.paymentreconciliationledgerjava.service.ReconciliationService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/reconciliation")
public class ReconciliationController {
    private final ReconciliationService reconciliationService;

    public ReconciliationController(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    public record ReconRequest(String statementId, List<ReconEntry> entries) {}

    @PostMapping
    public List<ReconItemResult> reconcile(@RequestBody ReconRequest request) {
        return reconciliationService.reconcile(request.statementId(), request.entries());
    }
}
