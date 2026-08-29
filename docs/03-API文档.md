# 酒店记账 · 经营分析 · AI 定价系统 —— HTTP API 契约文档

> 版本：v1.0 ｜ 状态：契约基线 ｜ 关联：`01-系统设计文档.md`、`02-数据库设计.md`、`04-UI原型说明.md`、`prototype/index.html`
>
> 本文是**前端（Vue）↔ 主后端（Java）之间唯一的接口契约**，也是主后端 ↔ 旁车（FastAPI）内部接口的说明。
> 前端**只**经 `/api` 与主后端交互，永远不直接调旁车、不直连 MySQL。
> 前端与后端以本文为准并行开发；任何接口变更须在此文档先行修订。

---

## 1. 通用约定

### 1.1 基础

| 项 | 约定 |
|---|---|
| 前缀 | 所有业务接口 `/api` 开头；旁车内部服务为 `http://127.0.0.1:8001`，**仅监听回环** |
| 内容 | `application/json; charset=utf-8`；上传用 `multipart/form-data` |
| 认证 | `Authorization: Bearer <JWT>`；除 §2 认证组外全部需要 |
| 月份 | `YYYY-MM`（如 `2026-08`） |
| 日期 | `YYYY-MM-DD`（如 `2026-08-22`） |
| 金额 | 一律元、两位小数，后端以 `DECIMAL` 返回字符串/数值（序列化禁止浮点） |
| 分页 | 查询参数 `page`（从 1 起）、`pageSize`；响应 `{ list, total, page, pageSize }` |

### 1.2 统一信封

```json
{ "code": 0, "message": "ok", "data": { } }
```

- `code = 0` 成功；`code ≠ 0` 为错误，`message` 给用户可读中文提示（前端可直接展示）。
- `data` 为业务数据；无返回体的接口 `data` 可为 `null`。

### 1.3 日期/月份口径

- 所有"月"统一 `YYYY-MM`；看板/图表趋势用 `from` + `to` 两个月份参数闭区间。
- 依赖"当年某月"的时间维度（如同比）用 `month` 单参数即可由后端推导前年。

### 1.4 分页响应

```json
{ "code": 0, "message": "ok",
  "data": { "list": [ ... ], "total": 137, "page": 1, "pageSize": 20 } }
```

---

## 2. 认证（单用户预留）

| # | Method | Path | 说明 |
|---|---|---|---|
| 2.1 | POST | `/api/auth/login` | 登录，返回 JWT |
| 2.2 | POST | `/api/auth/refresh` | 用刷新令牌换新 JWT |
| 2.3 | POST | `/api/auth/change-password` | 修改密码 |

**2.1 登录**

```jsonc
// 请求
{ "username": "admin", "password": "admin123" }
// 响应 data
{
  "token": "<JWT>",
  "refreshToken": "<JWT-refresh>",
  "expiresIn": 7200,
  "user": { "username": "admin", "displayName": "管理员" }
}
```

> 安全约定：JWT 短期（≤2h）+ 刷新令牌（≤7d）；密码 BCrypt 校验；无角色体系（单用户）。

---

## 3. 错误码表

> 前端按 `code` 分流提示；HTTP 状态码可统一 200（业务错误走信封），也可按 REST 语义（4xx/5xx），**由后端实现任选其一并统一**。本文推荐 REST 状态码 + 信封双轨。

| code | HTTP | 含义 | 前端处理 |
|---|---|---|---|
| 0 | 200 | 成功 | — |
| 40000 | 400 | 参数/请求体校验失败 | 展示 `message` |
| 40100 | 401 | 未登录或令牌无效 | 清本地令牌，跳登录页 |
| 40101 | 401 | 令牌过期 | 自动用 refreshToken 换新后重放一次 |
| 40300 | 403 | 无权限（预留） | — |
| 40400 | 404 | 资源不存在 | 展示 message |
| 40900 | 409 | 冲突（唯一键：重复日期/渠道名/重复导入等） | 展示 message |
| 50000 | 500 | 服务端内部错误 | 通用提示 + 日志 |
| 50100 | 503 | **旁车不可用**（解析/归类/预测） | 降级提示"智能服务暂不可用，仍可手动归类/纯日历预测" |
| 50200 | 503 | DeepSeek 调用失败 | 降级为纯统计预测，仍在 `prediction_result` 记录 |
| 50300 | 422 | 文件解析失败 / 模板格式不符 | 展示错误行号/原因 |

---

## 4. 模块总览（分组）

| 分组 | 章节 | 主要职责 |
|---|---|---|
| 认证 | §2 | 登录/刷新/改密 |
| 首页看板 | §5 | 月度总览卡 + 趋势 + 成本结构 + 渠道占比 + 对账摘要 |
| 成本分析 | §6 | 月度成本明细 CRUD + 分类/趋势 |
| 销售渠道 | §7 | 渠道字典 + 渠道×月统计 + 佣金调整 |
| 利润分析 | §8 | 月度利润表 + 同比 + 趋势 |
| 房态·入住率 | §9 | 按具体房间逐日登记 + 房间×日期矩阵 + 对账（差房） |
| 定价·预测 | §10 | 档位 CRUD + 目标倒推 + 临近日建议价 + 预测/LLM |
| 回本测算 | §11 | 参数 CRUD + 逐月现金流 + 敏感度 |
| 导入 | §12 | 模板下载 + 上传/解析/归类确认/落库 |
| 设置·基础数据 | §13 | 酒店配置 + 档位（复用 §10）+ 模板下载 + KV |
| 旁车内部 | §14 | 主后端 → 旁车 的调用面 |

---

## 5. 首页看板

| # | Method | Path | 说明 |
|---|---|---|---|
| 5.1 | GET | `/api/dashboard/overview?month=2026-08` | 总览卡：收入/成本/利润/间夜/ADR/入住率 + 环比 delta |
| 5.2 | GET | `/api/dashboard/trend?from=2026-01&to=2026-08` | 月度收入/成本/利润折线 |
| 5.3 | GET | `/api/dashboard/cost-structure?month=2026-08` | 成本结构：固定/变动/一次性 + TOP 5 费用项 |
| 5.4 | GET | `/api/dashboard/channel-ratio?month=2026-08` | 线上/线下占比 + 渠道 TOP |
| 5.5 | GET | `/api/dashboard/reconcile?month=2026-08` | 对账摘要（房态间夜 vs 流水间夜、diff） |

**5.1 overview 响应 data**

```jsonc
{
  "month": "2026-08",
  "revenue": 61960.00, "revenueDelta": 0.08,        // 环比 %
  "totalCost": 34500.00, "totalCostDelta": -0.02,
  "profit": 27460.00,   "profitDelta": 0.12,
  "nights": 198,        "nightsDelta": 0.05,
  "adr": 312.93,        "adrDelta": 0.03,
  "occupancyRate": 90.00, "occupancyRateDelta": 0.02
}
```

> `*Delta` 为环比涨跌幅（如 `0.08` = +8%）。前端给出 ↑/↓ 与着色。

**5.5 reconcile 响应 data**

```jsonc
{
  "month": "2026-08",
  "reconcileStatus": "matched",        // matched / diff / unchecked / none
  "occupancyNights": 198,              // Σ daily_occupancy.occupied_rooms
  "channelNights": 198,                // Σ channel_monthly.nights
  "diff": 0,
  "detailChannels": [                  // 差异定位到渠道（diff≠0 时前端提示"中介差房"排查）
    { "channelName": "协议 / 中介", "reportedNights": 27, "actualRoomNights": 30, "diff": 3 }
  ]
}
```

---

## 6. 成本分析

| # | Method | Path | 说明 |
|---|---|---|---|
| 6.1 | GET | `/api/costs?month=2026-08&type=&page=1&pageSize=20` | 月度成本明细列表，`type∈fixed/variable/one_time` |
| 6.2 | POST | `/api/costs` | 手工新增一条成本 |
| 6.3 | PUT | `/api/costs/{id}` | 修改（金额/类型/备注/名称） |
| 6.4 | DELETE | `/api/costs/{id}` | 删除 |
| 6.5 | GET | `/api/costs/summary?month=2026-08` | 三类小计 + 合计（成本结构饼图用） |
| 6.6 | GET | `/api/costs/trend?from&to` | 按月成本趋势（固定/变动/一次性三条线） |

**6.1 列表项 data.list[0]**

```jsonc
{
  "id": 12,
  "month": "2026-08",
  "costItemId": 6,
  "itemName": "布草洗涤",          // 快照：改名不影响历史
  "amount": 4200.00,
  "type": "variable",
  "note": null,
  "source": "manual",              // manual / import / mapping
  "importBatchId": null
}
```

**6.2 请求**

```jsonc
{ "month": "2026-08", "itemName": "消杀费", "amount": 800.00, "type": "variable", "note": "每月例行" }
```

> 手工新增时：若 `itemName` 命中/近似字典，主后端自动关联 `costItemId` 并存 `import_mapping_rule`（学习规则顺带增强）。

---

## 7. 销售渠道

| # | Method | Path | 说明 |
|---|---|---|---|
| 7.1 | GET | `/api/channels?type=&enabled=` | 渠道字典列表 |
| 7.2 | POST | `/api/channels` | 手动新增渠道（默认仅设置页"手动新增"入口） |
| 7.3 | PUT | `/api/channels/{id}` | 改佣金率 / 类型 / 停用 / 排序（**无专属维护页，入口在设置页**） |
| 7.4 | GET | `/api/channel-monthly?month=2026-08` | 渠道×月统计（入手价口径） |
| 7.5 | GET | `/api/channel-monthly/trend?from&to` | 渠道间夜/收入趋势 |

**7.4 响应 data.list[0]**

```jsonc
{
  "channelId": 1, "channelName": "携程", "type": "online",
  "nights": 66, "revenue": 20460.00, "grossRevenue": 23250.00,
  "commission": 2790.00, "commissionRate": 0.12,
  "avgPrice": 310.00,               // 到手均价 = revenue / nights
  "share": 0.33                     // 间夜占比（前端柱状/占比用）
}
```

> 口径提醒：`commissionRate` 当前值取 channel 表；`grossRevenue = revenue / (1 - rate)`（线上）。前端线上渠道可展示"挂牌 ≈ 到手×(1+率)"列。

---

## 8. 利润分析

| # | Method | Path | 说明 |
|---|---|---|---|
| 8.1 | GET | `/api/profit/monthly?from=2026-01&to=2026-08` | 逐月利润表（含同比，当年同期 ±） |
| 8.2 | GET | `/api/profit/summary?month=2026-08` | 单月利润表头（收入/成本/净利/间夜/ADR/单间夜利） |

**8.1 data.list[0]**

```jsonc
{
  "month": "2026-08",
  "revenue": 61960.00, "totalCost": 34500.00, "profit": 27460.00,
  "nights": 198, "adr": 312.93, "perNightProfit": 138.69,   // profit/nights
  "yoy": { "profit": 0.05, "revenue": 0.03, "nights": 0.02 }  // 同比涨跌（可选字段）
}
```

---

## 9. 房态 · 入住率

> 核心口径：**按具体房间登记**（哪天哪几间住了），不再填"笼统几间"。
> 后端写入 `daily_occupied_room` 明细 → 刷新 `daily_occupancy` 聚合（`occupied_rooms`/`occupancy_rate`）→ 刷新 `monthly_summary` 与 `reconcile_status`。房号未建档（`room` 表查不到）时**自动建档**。

| # | Method | Path | 说明 |
|---|---|---|---|
| 9.1 | GET | `/api/occupancy/daily?month=2026-08` | 该月每日聚合列表（含工作日标记、入住率） |
| 9.2 | PUT | `/api/occupancy/day-rooms` | **登记/覆盖某日入住的具体房间**（`biz_date` + 房间号数组） |
| 9.3 | GET | `/api/occupancy/day-rooms?bizDate=2026-08-23` | 当日已入住的房间列表 |
| 9.4 | GET | `/api/occupancy/matrix?month=2026-08` | **房间 × 日期 入住矩阵**（房态页主体） |
| 9.5 | POST | `/api/occupancy/batch` | 批量逐日补录（如补上周每天哪几间） |
| 9.6 | GET | `/api/occupancy/reconcile?month=2026-08` | 对账差异（同 5.5，房态详页版） |
| 9.7 | GET | `/api/occupancy/workday-rate?month=2026-08` | 工作日/周末拆分入住率（**分母口径=当月有房态记录的营业日 × 可售房间**，与 9.1/月结 occupancy_rate 的「可售×有数据营业日」一致；不用全月日历日，避免空档稀释） |

**9.2 请求**

```jsonc
{ "bizDate": "2026-08-23", "roomNos": ["101", "102", "205"], "note": null }
// roomNos 为空数组 = 该日全部空房（抹掉入住）；未建档房号自动创建
```

**9.1 data.list[0]**

```jsonc
{
  "bizDate": "2026-08-22", "occupiedRooms": 2, "totalRooms": 10,
  "occupiedRoomNos": ["101", "102"],
  "occupancyRate": 20.00, "isWeekend": false, "isHoliday": false,
  "source": "manual", "note": null
}
```

**9.4 matrix data**

```jsonc
{ "month": "2026-08",
  "rooms": [ { "roomNo": "101", "roomType": "大床房", "occupied": ["2026-08-01","2026-08-02", ...] } ],
  "legend": { "occupied": true }
}
```

**9.5 请求**

```jsonc
{ "rows": [ { "bizDate": "2026-08-16", "roomNos": ["101","103","201","202","204","205"] },
            { "bizDate": "2026-08-17", "roomNos": ["102","103","105", ...] } ] }
```

> 登记写入后由主后端异步刷新本月 `monthly_summary`（重算冗余表）与 `reconcile_status`。

---

## 10. 定价 · 预测

| # | Method | Path | 说明 |
|---|---|---|---|
| 10.1 | GET | `/api/pricing/tiers` | 档位列表 |
| 10.2 | POST | `/api/pricing/tiers` | 新增档位 |
| 10.3 | PUT | `/api/pricing/tiers/{id}` | 改档位 |
| 10.4 | DELETE | `/api/pricing/tiers/{id}` | 删除（已被建议引用的档位置 NULL 且历史不变） |
| 10.5 | GET | `/api/pricing/suggestions?from=2026-08-23&to=2026-09-05` | 逐日建议价列表 |
| 10.6 | POST | `/api/pricing/suggestions/generate` | 生成临近日建议价（引擎：档位×日历×节假日×预测入住率） |
| 10.7 | PUT | `/api/pricing/suggestions/{bizDate}` | 人工改价并锁定（`source=manual`） |
| 10.8 | GET | `/api/pricing/calc/target` | 目标倒推 · **纯计算**（GET 带参即算，不落库） |
| 10.9 | POST | `/api/pricing/calc/scenarios` | 保存目标倒推参数/结果 |
| 10.10 | GET | `/api/pricing/calc/scenarios` | 最近倒推记录 |
| 10.11 | POST | `/api/prediction/generate` | 生成月度预测（统计模型 + DeepSeek 解读，落库） |
| 10.12 | GET | `/api/prediction/results?target=2026-09` | 预测历史（含 LLM 解读） |
| 10.13 | GET | `/api/prediction/daily?date=&month=` | 单日/逐日预测（建议价联动用） |

**10.6 请求 / 响应**

```jsonc
// 请求
{ "from": "2026-08-23", "to": "2026-09-05" }
// 响应 data
{ "generated": 14, "items": [ { "bizDate": "2026-08-23", "suggestedPrice": 298.00,
                                "tierName": "周末价", "occupancyForecast": 92.00,
                                "isWeekend": true, "source": "engine" } ] }
```

**10.8 目标倒推（纯计算）**

```jsonc
// GET /api/pricing/calc/target?targetRevenue=80000&targetOccupancy=85&roomCount=10&daysPerMonth=30.4
// roomCount 为「假设值」，页面默认带入 room 表 enabled=1 的可售间数，用户可改（what-if）
// 响应 data
{
  "targetPrice": 309.6,          // = targetRevenue / (roomCount × daysPerMonth × occupancy%)
  "monthly": { "revenue": 80000, "nights": 258.4, "adrNeed": 309.6 }
}
```

**10.11 预测生成**

```jsonc
// 请求
{ "month": "2026-09", "metric": "revenue" }   // revenue / nights / occupancy_rate / adr / price
// 响应 data
{
  "target": "2026-09",
  "predictedValue": 74500.00,
  "engine": "statistical",                       // statistical / hybrid / llm
  "confidenceLow": 68200.00, "confidenceHigh": 80800.00,
  "llmInterpretation": "9月受中秋假期拉动，预计…建议周末价上调至 ¥380 …"
}
```

> 调用链路：主后端 → 旁车 `/predict`（统计模型）→（可选）旁车调 DeepSeek 解读（只送聚合摘要：历史月收入/间夜/入住率数字 + 费用名，**不送身份/敏感字段**）→ 落 `prediction_result`。

---

## 11. 回本测算

| # | Method | Path | 说明 |
|---|---|---|---|
| 11.1 | GET | `/api/breakeven/scenarios` | 参数方案列表 |
| 11.2 | POST | `/api/breakeven/scenarios` | 新建方案 |
| 11.3 | PUT | `/api/breakeven/scenarios/{id}` | 改参数（**触发现金流重算**） |
| 11.4 | DELETE | `/api/breakeven/scenarios/{id}` | 删除（级联删现金流） |
| 11.5 | GET | `/api/breakeven/scenarios/{id}/cashflow` | 逐月现金流 + 回本月份 |
| 11.6 | GET | `/api/breakeven/scenarios/{id}/sensitivity` | 回本月份敏感度（对月净流入/月供/投资额） |

**11.2 请求**

```jsonc
{
  "name": "捌宿·基准方案", "investment": 2000000.00, "ownCapital": 800000.00,
  "loanAmount": 1200000.00, "loanRate": 0.038, "loanYears": 10,
  "monthlyNetInflow": 20000.00
}
```

**11.5 响应 data**

```jsonc
{
  "scenario": { "id": 1, "name": "捌宿·基准方案", "monthlyPayment": 12035.68,
                "breakEvenMonth": 58, "breakEvenDate": "2031-06" },
  "rows": [ { "monthSeq": 1, "inflow": 20000.00, "outflow": 12035.68,
              "net": 7964.32, "runningBalance": -1192035.68, "remark": null } ]
}
```

> 现金流口径：月净流入（不含月供）− 等额本息月供 = 该月净额；`runningBalance` 累计，**首次 ≥ 0 即回本**。月供公式：`LOAN × (RATE/12) / (1 − (1 + RATE/12)^(−YEARS×12))`。
> ⚠ **示例数值更正（2026-08-24 二期开工）**：本示例 `breakEvenMonth: 58 / breakEvenDate: 2031-06` 为旧占位，与 seed 参数不符。按 seed 参数（投资 200 万 / 自有 80 万 / 贷款 120 万 / 3.8% / 10 年 / 月净流入 2 万）实算：月供 **12035.68**、起步 runningBalance = −1200000、月净额 7964.32 → 回本月 = ⌈1200000 ÷ 7964.32⌉ = **151**，回本日期 **2039-03**。**以 `07-Java主后端工单.md` §BE-10 验收为准**。

---

## 12. 导入（Excel 记账闭环 · 核心）

> **自动化优先契约（硬约束）**：费用项、渠道、**房号**在导入确认时**自动识别建档**，不做专属维护页。
> 导入流程状态机：`uploaded → parsed → mapped → confirmed / failed`（见 import_batch）。
> **模板三 Sheet**：①费用流水（列：名称/金额/类型/备注）②每日房态（列：**日期/房号**——按具体房间登记，未建档房号自动建档；可选天数列兼容老表）③渠道销售（列：渠道/间夜/到手收入…）。

| # | Method | Path | 说明 |
|---|---|---|---|
| 12.1 | GET | `/api/imports/template` | 下载「月度记账模板.xlsx」（三 Sheet） |
| 12.2 | POST | `/api/imports` | 上传 Excel（multipart `file` + `month`），进入 parsed |
| 12.3 | GET | `/api/imports/{id}` | 批次详情（文件、状态、行数） |
| 12.4 | GET | `/api/imports/{id}/preview` | 解析后三表预览（confirm 前给用户看） |
| 12.5 | GET | `/api/imports/{id}/mapping` | 智能归类建议（raw_name → cost_item + 置信度 + 类型） |
| 12.6 | POST | `/api/imports/{id}/confirm` | 确认落库（含费用项/渠道自动建档）→ 对账 |
| 12.7 | GET | `/api/imports?month=&status=&page=&pageSize=` | 导入历史 |

**12.2 响应**

```jsonc
{ "batchId": 37, "status": "parsed", "month": "2026-08",
  "fileName": "8月记账.xlsx", "totalRows": 128, "failedRows": 0,
  "rawNameSummary": "8月：电费/水费/携程/美团…" }
```

**12.5 mapping 响应 data**

```jsonc
{ "confirmed": 120,        // 高置信度自动建议数
  "needReview": 8,         // 需人工确认数
  "items": [ { "rowNo": 5, "rawName": "电费", "suggestCostItemId": 1,
               "suggestType": "fixed", "confidence": 0.98, "matched": true },
             { "rowNo": 42, "rawName": "奶茶", "suggestCostItemId": null,
               "suggestType": "variable", "confidence": 0.55, "matched": false } ] }
```

**12.6 confirm 请求（前端把用户改过的映射回传）**

```jsonc
{
  "mappings": [
    { "rawName": "电费", "costItemId": 1, "type": "fixed" },
    { "rawName": "奶茶", "costItemId": null, "type": "variable" }   // 未建：confirm 时自动新建费用项
  ],
  "channelRows": [ { "rawName": "携程", "channelId": 1 } ],         // 未匹配渠道自动建档
  "roomRows": [ { "roomNo": "101", "roomType": null } ]             // 未建档房号自动创建（房型/楼层可后续在设置页补）
}
```

**12.6 响应 data**

```jsonc
{ "batchId": 37, "status": "confirmed",
  "createdCostItems": [ "奶茶" ],
  "createdChannels": [],
  "createdRooms": [ "101", "103" ],
  "importedCosts": 34, "importedNights": 198,
  "reconcileStatus": "matched", "reconcileDiff": 0 }
```

> 落库动作（主后端负责）：写 `monthly_cost`（item_name 快照 + 关联 costItemId，plus `import_batch_id`）、**`daily_occupied_room`（房号明细 → 刷新 `daily_occupancy` 聚合）**、`channel_monthly`；对未匹配渠道/费用项/**房号**自动插入字典并记住 `import_mapping_rule`（is_manual=1 提高置信度）；随后刷新 `monthly_summary` 并对账。**唯一键冲突（同月重复导入）返回 40900**，用户可先覆盖删除旧批次。

---

## 13. 设置 · 基础数据

> 遵循"主数据自动化优先"：本组**不提供费用项/渠道 CRUD**（费用项、渠道、**房号**均尽量由导入确认自动建档）；只维护导入无法自动获知的信息：酒店配置、房间的房型/楼层/停用、渠道佣金率（§7）、档位价目（§10）、模板下载。

| # | Method | Path | 说明 |
|---|---|---|---|
| 13.1 | GET | `/api/settings/hotel` | 酒店配置（读取） |
| 13.2 | PUT | `/api/settings/hotel` | 保存酒店配置 |
| 13.3 | GET | `/api/settings/kv?key=llm.model` | 读通用 KV |
| 13.4 | PUT | `/api/settings/kv` | 写通用 KV（白名单 key） |
| 13.5 | GET | `/api/imports/template` | 模板下载（同 12.1，设置页复用） |
| 13.6 | GET | `/api/rooms?enabled=1&keyword=` | 房间字典列表（分页） |
| 13.7 | POST | `/api/rooms` | 新增房间（房号唯一，重复 40900 冲突，可改启用状态） |
| 13.8 | PUT | `/api/rooms/{id}` | 修改房型/楼层/排序/停用 |
| 13.9 | POST | `/api/rooms/{id}/disable` | 停用房间（软删；有历史流水也允许，禁售不删行） |

**13.2 请求**

```jsonc
{ "hotelName": "捌宿轻居",
  "city": "杭州",              // 可空：天气预测降级
  "defaultCommissionRate": 0.12,
  "daysPerMonth": 30.4 }      // 目标倒推常数（默认 30.4）
```

> 保存后主后端校验 `0 ≤ defaultCommissionRate < 1`；`hotel_config` 为单行（id=1）UPSERT。
> **可售房间数不由本接口维护**：=`room` 表 `enabled=1` 计数，供入住率 / 目标倒推 / 回本共用（见 §13.6–13.9、§10.8、§11）。

**13.7/13.8 请求**

```jsonc
{ "roomNo": "301", "roomType": "大床房", "floor": "3F", "sortOrder": 11 }
// PUT 可只带要改字段；roomNo 只允许在建档时设置，历史关联以 id 稳定
```

**13.6 data.list[0]**

```jsonc
{ "id": 11, "roomNo": "301", "roomType": "大床房", "floor": "3F",
  "enabled": true, "sortOrder": 11,
  "firstSeenFrom": "manual" }   // manual / import:{batchId}：房号自动建档来源
```

---

## 14. 旁车内部接口（主后端 → FastAPI，仅 127.0.0.1）

> 旁车无 JWT/权限；仅回环可达。均 POST，JSON。所有错误由旁车以 `code/message` 返回，主后端**统一翻译为信封**并映射 §3 错误码（50100/50200/50300）。

| # | Method | Path | 说明 |
|---|---|---|---|
| 14.1 | POST | `/api/parse` | 解析 Excel（openpyxl）→ 结构化三表 JSON + 归类建议（房态 Sheet 解析为日期×房号明细） |
| 14.2 | POST | `/api/categorize` | 批量智能归类（历史兜底；与 12.5 数据同源） |
| 14.3 | POST | `/api/predict` | 时序预测（统计模型：日历 + 天气[按 hotel_config.city 可空降级] + 节假日）→ 预测值 + 置信区间 |
| 14.4 | POST | `/api/llm/interpret` | DeepSeek 解读（只送聚合摘要，见 §10 提示） |
| 14.5 | GET | `/api/health` | 健康检查（主后端轮询旁路，故障降级） |

**14.3 请求（主后端汇总后提交，不含明细行）**

```jsonc
{ "target": "2026-09", "metric": "revenue",
  "history": [ { "month": "2026-01", "value": 41200.0 }, ... ],
  "occupancyWindow": [ { "date": "2026-08-20", "rate": 90.0 } ],
  "city": "杭州", "economicHolidays": ["2026-09-16", ...] }
```

**14.4 请求摘要口径（脱敏）**

```jsonc
{ "metric": "revenue", "predictedValue": 74500.0,
  "historyHeadline": "近8月收入 41200→61960，环比 +8%",
  "ask": "解读趋势并给出定价/经营建议，200字内" }
```

> 红线：向 DeepSeek 出网只送聚合数字与费用名摘要；**不送**任何身份证类、政治/安全敏感字段、完整个人数据。

---

## 15. 工单式组织建议

- **前端**只依赖 §1–§13 与 `04-UI原型说明.md`；
- **Java 主后端**实现 §2–§13 + 对账/汇总重算 + 调用 §14；
- **Python 旁车**实现 §14；
- 契约修订：改本文 → 同步改 `02-数据库设计.md` / `04-UI原型说明.md` → 三端各自更新。任何接口与本文不符即以本文为准。
