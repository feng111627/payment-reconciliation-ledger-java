# 支付指令受理与对账结算（本地内存账本）

一个基于 Spring Boot 的本地服务：用**本地内存存储**模拟账户、支付指令、账本流水与对账单，
实现“支付指令受理（幂等）→ 批量结算（支持部分结算）→ 本地对账（乱序/畸形/差异可解释）→
确认/冲正”的完整闭环。所有资金口径变动都以**追加式账本流水**留痕，保证账本守恒、结论可复现。

- JDK 21+、Spring Boot 3.4、无外部数据库依赖
- 启动时自动创建演示账户：`ACC-CNY-001`（CNY，余额 1000）、`ACC-USD-001`（USD，余额 1000）

---

## 1. 核心模型与账本守恒

账户三个资金口径：

| 口径 | 含义 |
|---|---|
| `available` 可用余额 | 可用于新支付冻结的钱 |
| `frozen` 冻结 | 受理后、结算前被冻结的本金 + 手续费 |
| `inflight` 在途 | 已结算、尚未对账确认出账的本金 |

账户总额恒等式：

```
total = available + frozen + inflight
```

每一次资金变动都在 `LedgerService`（账户级互斥锁）内完成“校验 → 改口径 → 追加一条流水”，
流水类型与口径影响：

| 流水类型 | 口径变化 | 对 total 的影响 |
|---|---|---|
| `FREEZE` | available → frozen | 不变 |
| `SETTLE` | frozen → inflight | 不变 |
| `FEE` | frozen → 账户外（手续费出账，仅全额结算时收取） | 减少 |
| `UNFREEZE` | frozen → available（未结算部分冲正） | 不变 |
| `REVERSE` | inflight → available（已结算部分冲正） | 不变 |
| `CONFIRM` | inflight → 账户外（对账一致确认出账） | 减少 |

> 手续费规则：受理时与本金一起冻结；**全额结清的同一次结算动作**中出账；
> 全额结算之前冲正，手续费随冻结一并退回；**全额结算之后再冲正，已收手续费不退**（业务上可解释为结算服务已完成）。

---

## 2. 支付受理幂等规则

入口：`POST /api/payments`，以 `bizKey`（业务号）为幂等键。

1. **同一 bizKey 只有首次请求生效**。重复提交（含并发，服务端按 bizKey 加锁 + 二次检查 +
   `ConcurrentMap.putIfAbsent`）直接返回首次结果，响应中 `replayed=true`，
   **不重复扣款、不多记一笔指令或流水**。
2. **请求指纹**：`accountId|amount|currency|fee`。同一 bizKey 但指纹不同 →
   `400 IdempotencyConflictException`，明确拒绝，不能当作同一次请求。
3. **首次失败也登记幂等**：被拒绝的请求同样记录首次结论；之后用相同内容重试仍返回首次拒绝结果，
   不会因为环境变化（如账户被充值）而悄悄变成成功。
4. **拒绝原因可区分**（`rejectReason` 字段，绝不静默丢弃、绝不伪装成功）：

| rejectReason | 触发条件 |
|---|---|
| `INVALID_AMOUNT` | 金额为 null / 非正数 / 手续费为负 |
| `CURRENCY_NOT_SUPPORTED` | 币种缺失或不在 CNY/USD/EUR 内 |
| `ACCOUNT_NOT_FOUND` | 账户不存在 |
| `CURRENCY_MISMATCH` | 支付币种与账户币种不一致 |
| `INSUFFICIENT_BALANCE` | 可用余额 < 本金 + 手续费（锁内权威校验，并发安全） |

---

## 3. 状态机

所有入口（受理、批量结算、对账触发确认/冲正）共用 `PaymentStatus` 内的同一套流转规则，
非法流转统一抛 `IllegalStateTransitionException`（带 from/to，可解释）：

```
受理成功        ───────────────► ACCEPTED（本金+手续费已冻结）

部分结算        ACCEPTED / PARTIALLY_SETTLED ─► PARTIALLY_SETTLED
全额结算        ACCEPTED / PARTIALLY_SETTLED ─► SETTLED（手续费同步出账）

对账一致        SETTLED ─► CONFIRMED（在途本金出账，终态）

冲正            ACCEPTED / PARTIALLY_SETTLED / SETTLED ─► REVERSED（终态）
                 · ACCEPTED：冻结的本金+手续费全部解冻
                 · PARTIALLY_SETTLED：已结算本金 从在途退回；未结算本金+手续费 解冻
                 · SETTLED：在途本金退回，已收手续费不退
```

- `CONFIRMED`、`REVERSED` 是**终态**：对终态指令再结算、再冲正都会被明确拒绝。
- 结算金额要求：本次为正数、累计不得超过指令金额；超限直接拒绝，**不多扣冻结、状态不变**。
- 冲正按当前状态计算回退口径，重复冲正不可能发生（状态机 + bizKey 锁双重保证）。

---

## 4. 批量结算规则

入口：`POST /api/settlements`，请求体 `{"batchId": "...", "items":[{"bizKey","amount"}]}`。

1. **批次幂等**：同一 `batchId` 重复提交（含并发）不重复执行、不重复记账，返回 `replayed=true`。
2. **支持部分结算**：单笔指令可分多批结算；刚好结清的那次把状态推进到 `SETTLED` 并收取手续费。
3. 批内按 bizKey 排序处理，结果顺序稳定，避免交叉加锁。
4. 结算到不存在的指令、终态指令、金额非法都会被明确拒绝。

---

## 5. 对账判定规则

入口：`POST /api/reconciliations`，请求体：
`{"batchId":"...","autoReverse":false,"entries":[{"bizKey","amount","currency"}]}`。

**不以任何一方默认为准**，逐条按字段给出可区分结论：

| 结论 EntryConclusion | 判定条件 |
|---|---|
| `MALFORMED_ENTRY` | 条目缺 bizKey / 缺 amount / 缺 currency（或币种字符串无法解析） |
| `LOCAL_MISSING` | 对账单有条目，本地无对应支付指令 |
| `STATEMENT_MISSING` | 本地有支付指令，对账单没有对应条目 |
| `CURRENCY_MISMATCH` | 币种都合法但彼此不同（先于金额比较） |
| `AMOUNT_MISMATCH` | 币种一致但金额（两位小数精确比较）不同 |
| `MATCHED` | bizKey、币种、金额全部一致 |

其他规则：

- **乱序安全**：始终按 bizKey 匹配，与条目到达顺序无关；输出结论按 bizKey 排序，结论可复现。
  对账单先于结算到达时，`MATCHED` 的明细会标明本地仍是 `ACCEPTED`，不会提前确认出账。
- **批次幂等**：同一 `batchId` 重复对账直接返回首次结论（`replayed=true`），
  **不重复记账、不重复冲正**；结论持久化在对账记录表中。
- **批内重复条目**：同一 bizKey 在一份对账单中出现多次，只评估一次，明细标注“批内重复条目”。
- **自动处置**（`autoReverse=true`，默认关闭）：
  - `MATCHED` 且本地为 `SETTLED` → 自动确认出账（`CONFIRMED`）；
  - `AMOUNT_MISMATCH` / `CURRENCY_MISMATCH` 且指令可冲正 → 自动冲正（`REVERSED`）；
    若已处于终态则不重复冲正，并在 actions 中说明；
  - `LOCAL_MISSING` / `STATEMENT_MISSING` / `MALFORMED_ENTRY` **只出结论不自动处置**，
    避免在事实不清时改动账务。

---

## 6. HTTP 接口速览

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/payments` | 受理支付（幂等） |
| POST | `/api/settlements` | 批量结算（批次幂等，可部分结算） |
| POST | `/api/reconciliations` | 提交对账单对账（批次幂等） |
| GET  | `/api/accounts/{id}` | 查询账户三口径与总额 |
| GET  | `/api/payments/{bizKey}` | 按业务号查支付指令 |
| GET  | `/api/payments` | 全部支付指令 |
| GET  | `/api/ledger` | 全部账本流水（审计/核对） |

业务错误统一响应 `400 {"code": "...", "message": "..."}`；受理类业务拒绝（余额不足等）
在 200 响应体中以 `accepted=false` + `rejectReason` 表达（属于明确的业务结论而非系统错误）。

---

## 7. 验证方法

### 运行全部测试

```bash
mvn test
```

共 31 个用例，按业务模块组织：

| 测试类 | 覆盖业务 |
|---|---|
| `IdempotentAcceptanceTest`（9） | 首次冻结、重复提交重放、指纹冲突、余额不足/币种不符/币种不支持/金额非法/账户不存在、首次失败也幂等 |
| `ConcurrentIdempotencyTest`（2） | 32 线程并发同一 bizKey 只生效一次；低余额账户并发扣款不允许透支且总额守恒 |
| `ReconciliationDisorderTest`（8） | 乱序到达结论一致、对账单先到、金额/币种不符、双方缺失、字段缺失畸形、重复对账单幂等、批内重复条目 |
| `StateTransitionAndLedgerTest`（8） | 部分结算口径、超额结算拒绝、三种时点冲正、对账确认出账、终态非法流转、结算批次幂等 |
| `HttpEndToEndTest`（3） | HTTP 入口端到端幂等、拒绝原因、乱序对账与重复批次 |

### 查看“覆盖了哪些业务、用例与通过情况”的日志

测试日志扩展（`ScenarioLoggingExtension`，JUnit ServiceLoader 全局注册）会在每个测试类的
输出文件中按业务模块打印逐条用例结果：

```
target/surefire-reports/<测试类全限定名>-output.txt
```

内容形如：

```
================ 业务模块: 支付受理与幂等 ================
[运行] 支付受理与幂等 > 同一业务号重复提交只生效一次...
  [通过] 同一业务号重复提交只生效一次...
---------------- 支付受理与幂等 小结: 通过 9 / 失败 0 ----------------
```

### 手工冒烟（启动服务后）

```bash
mvn spring-boot:run

# 1) 受理（执行两次观察第二次 replayed=true）
curl -s -X POST localhost:8080/api/payments -H 'Content-Type: application/json' \
  -d '{"bizKey":"BIZ-DEMO","accountId":"ACC-CNY-001","amount":"100.00","currency":"CNY","fee":"2.00"}'

# 2) 批量结算（可分两批部分结算，金额合计 100.00）
curl -s -X POST localhost:8080/api/settlements -H 'Content-Type: application/json' \
  -d '{"batchId":"SET-DEMO-1","items":[{"bizKey":"BIZ-DEMO","amount":"100.00"}]}'

# 3) 对账（故意把条目顺序打乱、混入缺失/差异/畸形数据，观察可区分结论）
curl -s -X POST localhost:8080/api/reconciliations -H 'Content-Type: application/json' \
  -d '{"batchId":"ST-DEMO-1","autoReverse":true,"entries":[
       {"bizKey":"BIZ-GHOST","amount":"10.00","currency":"CNY"},
       {"bizKey":"BIZ-DEMO","amount":"100.00","currency":"CNY"}]}'

# 4) 核对账户口径与流水
curl -s localhost:8080/api/accounts/ACC-CNY-001
curl -s localhost:8080/api/ledger
```
