package com.github.feng111627.paymentreconciliationledgerjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.feng111627.paymentreconciliationledgerjava.tests.support.BusinessScenario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@BusinessScenario("HTTP 端到端入口")
@SpringBootTest
@AutoConfigureMockMvc
class HttpEndToEndTest {

    @Autowired
    private MockMvc mockMvc;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("HTTP 重复提交同一业务号：首次成功，第二次 replayed=true")
    void httpDuplicatePaymentIsIdempotent() throws Exception {
        String body = """
                {"bizKey":"HTTP-1","accountId":"ACC-CNY-001","amount":"100.00",
                 "currency":"CNY","fee":"1.00"}
                """;
        mockMvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.replayed").value(false));

        mockMvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.replayed").value(true));
    }

    @Test
    @DisplayName("HTTP 余额不足：返回可区分的拒绝原因，不报错 5xx")
    void httpInsufficientBalanceReturnsReason() throws Exception {
        String body = """
                {"bizKey":"HTTP-2","accountId":"ACC-CNY-001","amount":"999999.00",
                 "currency":"CNY","fee":"0.00"}
                """;
        mockMvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.rejectReason").value("INSUFFICIENT_BALANCE"));
    }

    @Test
    @DisplayName("HTTP 乱序对账：颠倒条目的对账单得到一致结论；重复批次 replayed")
    void httpReconciliationOutOfOrderAndReplay() throws Exception {
        // 受理 + 全额结算两笔
        for (String key : List.of("HTTP-PA", "HTTP-PB")) {
            String payBody = "{\"bizKey\":\"" + key + "\",\"accountId\":\"ACC-CNY-001\","
                    + "\"amount\":\"10.00\",\"currency\":\"CNY\",\"fee\":\"0.00\"}";
            mockMvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON).content(payBody))
                    .andExpect(status().isOk());
            String settleBody = "{\"batchId\":\"SET-" + key + "\",\"items\":[{\"bizKey\":\""
                    + key + "\",\"amount\":\"10.00\"}]}";
            mockMvc.perform(post("/api/settlements").contentType(MediaType.APPLICATION_JSON).content(settleBody))
                    .andExpect(status().isOk());
        }

        String reconBody = """
                {"batchId":"ST-HTTP-1","autoReverse":false,
                 "entries":[{"bizKey":"HTTP-PB","amount":"10.00","currency":"CNY"},
                            {"bizKey":"HTTP-PA","amount":"10.00","currency":"CNY"},
                            {"bizKey":"HTTP-GHOST","amount":"10.00","currency":"CNY"}]}
                """;
        String first = mockMvc.perform(post("/api/reconciliations")
                        .contentType(MediaType.APPLICATION_JSON).content(reconBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(false))
                .andReturn().getResponse().getContentAsString();
        JsonNode tree = mapper.readTree(first);
        // 结果按 bizKey 排序：GHOST/PA/PB；GHOST 为 LOCAL_MISSING
        assertEquals("HTTP-GHOST", tree.get("entries").get(0).get("bizKey").asText());
        assertEquals("LOCAL_MISSING", tree.get("entries").get(0).get("conclusion").asText());

        // 重复对账
        mockMvc.perform(post("/api/reconciliations")
                        .contentType(MediaType.APPLICATION_JSON).content(reconBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));
    }

    private static void assertEquals(Object expected, Object actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }
}
