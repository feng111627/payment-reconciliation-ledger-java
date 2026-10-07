# 支付指令受理与对账结算（本地模拟）

基于 Spring Boot 的支付受理、批量结算与本地对账能力。账户、支付指令、账本流水、对账结论全部使用本地内存存储（`ConcurrentHashMap` / `CopyOnWriteArrayList`），模拟真实账务语义：幂等受理、账本守恒、状态机流转、对账差异冲正。

## 运行与验证

```bash
mvn test          # 运行全部单测（14 个用例）
mvn spring-boot:run   # 启动本地服务
```

单测日志中带 `[业务:xxx]` 前缀的行标明每个用例覆盖的业务点与通过结论，例如：

```
[业务:支付受理-幂等] 用例:并发重复提交(16线程)
[业务:支付受理-幂等] 用例通过:accepted=1 duplicated=15 frozen=50.00
[业务:对账] 用例通过:5种结论可区分,差异条目已冲正, balance=1000.00
[业务:账本守恒] biz-l1 守恒: freezeIn=100.00 {DEBIT=100.00, FEE=0.10, ...}
```

测试类与业务覆盖对应关系：

| 测试类 | 覆盖业务 |
|---|---|
| `PaymentIdempotencyTest` | 幂等重复提交、并发重复提交（16 线程）、余额不足/币种不符/金额非法/账户不存在的可区分拒绝 |
| `SettlementAndStateMachineTest` | 批量全额结算、部分结算、手续费只收一次、冲正只生效一次、非法状态流转拒绝 |
| `ReconciliationTest` | 乱序对账、畸形/差异条目结论区分、重复对账幂等复现、跨对账单差异冲正幂等 |
| `LedgerConservationTest` | 提交/结算/对账冲正/手工冲正多入口混合后的账本守恒校验 |

## 幂等规则

- **幂等键 = 业务号（idempotencyKey）**，是支付指令的唯一约束。
- 同一幂等键重复提交：返回首次受理结果（`duplicate=true`），**不重复冻结、不重复记账**。拒绝类提交（余额不足等）抛异常，不占用幂等键。
- 并发重复提交：每个幂等键一把锁，锁内先查后写，并发下**有且仅有一个请求被受理**（测试：16 线程并发提交同一键，accepted=1，冻结只发生一次）。
- 对账幂等：以 `statementId + bizNo` 为键落对账结论；同一份数据重复对账**复现首次结论**（`replayed=true`），不重复落记录、不重复冲正。
- 冲正幂等：`REVERSED` 是终态，重复冲正（含跨对账单触发）被状态机拒绝/跳过，不重复退款。

## 对账判定规则

每条对账条目独立判定（乱序到达不影响结论），按以下优先级得出**唯一且可区分**的结论，不默认以任何一方为准：

| 优先级 | 条件 | 结论 | 副作用 |
|---|---|---|---|
| 1 | bizNo / amount / currency 任一缺失 | `MISSING_REQUIRED_FIELDS` | 无（无法定位键，不落库） |
| 2 | 同 statementId+bizNo 已有结论 | 复现首次结论（`replayed=true`） | 无 |
| 3 | 支付指令不存在 | `PAYMENT_NOT_FOUND` | 落结论 |
| 4 | 币种不符 | `CURRENCY_MISMATCH` | 落结论 + 触发冲正 |
| 5 | 金额不一致 | `AMOUNT_MISMATCH` | 落结论 + 触发冲正 |
| 6 | 全部一致 | `MATCHED` | 落结论 |

差异（金额/币种不符）一律触发对该支付指令的**冲正**：未结算部分解冻退回，已结算部分冲正入账并退手续费——账目回到受理前，等待人工介入，而不是静默采信某一方。

## 状态机

```
(none) --submit--> RECEIVED
RECEIVED            --settle(全额)--> SETTLED
RECEIVED            --settle(部分)--> PARTIALLY_SETTLED
PARTIALLY_SETTLED   --settle(补足)--> SETTLED
RECEIVED / PARTIALLY_SETTLED / SETTLED --reverse--> REVERSED
```

- 非法流转（如 `SETTLED -> SETTLED`、`REVERSED -> SETTLED`、重复冲正）抛出 `IllegalTransitionException`，HTTP 层映射为 `409`。
- 提交、批量结算、对账触发冲正三个入口**共用同一状态机与同一账本服务**，流转语义一致。
- 受理拒绝原因（`RejectReason`）：`INSUFFICIENT_BALANCE` / `CURRENCY_MISMATCH` / `INVALID_AMOUNT` / `ACCOUNT_NOT_FOUND` / `DUPLICATE_IDEMPOTENCY_KEY` / `ILLEGAL_STATE_TRANSITION`，HTTP 层映射为 `422` 并携带原因码。

## 账本守恒口径

账户口径：`balance`（可用）、`frozen`（冻结）、`inTransit`（在途）、`feeTotal`（累计手续费）。任何变动都在账户级锁内完成并追加一条 `LedgerEntry` 流水：

| 操作 | 资金路径 | 流水 |
|---|---|---|
| 受理 | balance → frozen | `FREEZE(+)` |
| 结算 | frozen → inTransit → 出账 | `FREEZE(-)` + `DEBIT` |
| 结清 | balance → feeTotal | `FEE`（费率 0.1%，只在全额结清时收一次） |
| 冲正(未结算) | frozen → balance | `UNFREEZE` |
| 冲正(已结算) | 退回 balance | `REVERSAL_CREDIT`，并退手续费（负向 `FEE`） |

守恒不变式（`LedgerConservationTest` 逐指令校验）：**冻结流入总额 = 解冻 + 出账**，且冲正退回 ≤ 出账额。部分结算、冲正、回滚任意组合后，`frozen` 与 `inTransit` 必须归零（全部结清或全部退回），不留悬挂资金。

## API 一览

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/accounts` | 创建账户（本地模拟） |
| GET | `/api/accounts/{id}` | 查询账户各口径余额 |
| POST | `/api/payments` | 受理支付指令（幂等） |
| POST | `/api/payments/settle` | 批量全额结算 |
| POST | `/api/payments/{key}/reverse` | 手工冲正 |
| POST | `/api/reconciliation` | 提交本地对账单对账 |
