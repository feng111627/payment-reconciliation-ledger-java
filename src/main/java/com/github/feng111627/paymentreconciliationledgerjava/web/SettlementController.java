package com.github.feng111627.paymentreconciliationledgerjava.web;

import com.github.feng111627.paymentreconciliationledgerjava.domain.SettlementBatch;
import com.github.feng111627.paymentreconciliationledgerjava.dto.SettlementResult;
import com.github.feng111627.paymentreconciliationledgerjava.service.SettlementService;
import org.springframework.web.bind.annotation.*;

/** 批量结算入口：POST /api/settlements（batchId 幂等）。 */
@RestController
@RequestMapping("/api/settlements")
public class SettlementController {

    private final SettlementService settlementService;

    public SettlementController(SettlementService settlementService) {
        this.settlementService = settlementService;
    }

    @PostMapping
    public SettlementResult settle(@RequestBody SettlementBatch batch) {
        return settlementService.settleBatch(batch);
    }
}
