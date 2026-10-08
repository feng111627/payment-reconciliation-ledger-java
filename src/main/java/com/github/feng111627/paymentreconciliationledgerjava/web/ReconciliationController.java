package com.github.feng111627.paymentreconciliationledgerjava.web;

import com.github.feng111627.paymentreconciliationledgerjava.domain.CurrencyCode;
import com.github.feng111627.paymentreconciliationledgerjava.domain.StatementBatch;
import com.github.feng111627.paymentreconciliationledgerjava.domain.StatementEntry;
import com.github.feng111627.paymentreconciliationledgerjava.dto.EntryDto;
import com.github.feng111627.paymentreconciliationledgerjava.dto.ReconcileRequest;
import com.github.feng111627.paymentreconciliationledgerjava.dto.ReconcileResult;
import com.github.feng111627.paymentreconciliationledgerjava.error.PaymentValidationException;
import com.github.feng111627.paymentreconciliationledgerjava.service.ReconciliationService;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 对账入口：POST /api/reconciliations。
 * 币种字符串无法识别时保留 null，交给对账服务判定为 MALFORMED_ENTRY（字段无法解析），
 * 而不是在入口抛 400 把畸形数据静默挡掉。
 */
@RestController
@RequestMapping("/api/reconciliations")
public class ReconciliationController {

    private final ReconciliationService reconciliationService;

    public ReconciliationController(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @PostMapping
    public ReconcileResult reconcile(@RequestBody ReconcileRequest request) {
        if (request == null || request.batchId() == null || request.batchId().isBlank()) {
            throw new PaymentValidationException("对账单批次号不能为空", null);
        }
        List<StatementEntry> entries = new ArrayList<>();
        for (EntryDto dto : request.entries() == null ? List.<EntryDto>of() : request.entries()) {
            CurrencyCode currency = null;
            if (dto.currency() != null && !dto.currency().isBlank()) {
                try {
                    currency = CurrencyCode.valueOf(dto.currency().trim());
                } catch (IllegalArgumentException ignored) {
                    currency = null;
                }
            }
            entries.add(new StatementEntry(dto.bizKey(), dto.amount(), currency));
        }
        return reconciliationService.reconcile(
                new StatementBatch(request.batchId(), entries, Instant.now(), false, null),
                request.autoReverse());
    }
}
