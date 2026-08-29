# Java 主后端（Spring Boot）开发工单 —— 一期

> **接口契约**：`03-API文档.md` 是唯一行为依据（路径/字段/错误码/时序都必须与它一致）。
> **库设计**：`02-数据库设计.md` + `db/schema.sql`（权威 DDL）+ `db/seed.sql`（演示种子）。
> 目录：`backend/`。公共契约先读 `05-开发工单总览.md` §2。
> 一期范围：BE-01 ~ BE-08；BE-09/10 为【二期】。

## 技术选型（建议，可换；接口/时序包装不可换）

- Java 17 + Spring Boot 3.x；MyBatis-Plus 3.5.x（或 JPA，自选）连 MySQL 8。
- 密码 BCrypt（`spring-security-crypto` 或 jBCrypt）；JWT 用 jjwt；金额 `BigDecimal`。
- `DECIMAL` 映射为 `BigDecimal`，**JSON 序列化为字符串**（统一 `JsonSerializer` 或 jackson 配置）。

## 横切实现（先做，所有 ticket 依赖）

- 信封包装：`ApiResult{code,message,data}`；全局 `@RestControllerAdvice` 把业务异常映射到 03 §3 错误码（40000/40100/40400/40900/50000/50100/50200/50300）。
- HTTP 状态码：按 03 §3 表（建议 REST 语义 + 信封双轨）。
- JWT：登录签发 access(≤2h)+refresh(≤7d)；拦截器校验 `Authorization: Bearer`；白名单 `/api/auth/login`、`/api/auth/refresh`。
- 日志：`logs/backend-%date.log` 保留 14 天；金额相关操作打审计日志（谁/何时/改了什么金额）。
- 日期/时区：应用统一 `Asia/Shanghai`；所有 `LocalDate/LocalDateTime`；HTTP 出入 `YYYY-MM-DD`/`YYYY-MM-DD HH:mm:ss`。
- 启动初始化：`schema.sql` 已由用户手动执行；应用内**不**自动建表（避免与权威 DDL 分叉）。可提供 `/api/health`（返回版本与 UP）。

---

## BE-01 认证 + 系统健康

**要做的**（03 §2）
- `POST /api/auth/login`：校验 bcrypt → 发 JWT + refreshToken + user 信息。
- `POST /api/auth/refresh`；`POST /api/auth/change-password`（校验旧密码）。

**验收**：admin/admin123 登录成功拿 token；错密码 40100；带错/过期 token 访问受保护接口 40101；改密后旧密失效。

## BE-02 主数据（设置页全部数据）

**要做的**
- 酒店配置（§13.1/13.2）：单行 `hotel_config`（id=1）GET/PUT（校验 `0 ≤ defaultCommissionRate < 1`；`daysPerMonth` 存 `app_setting`）。
- 房间（§13.6-13.9，表 `room`）：列表（分页，`enabled`/`keyword` 过滤）→ 新增（`room_no` 唯一，重复 40900）→ 修改（房型/楼层/排序/`enabled` 停用）→ 停用接口。
- 档位价目（§10.1-10.4，表 `pricing_tier`）CRUD；启用/停用状态。
- 渠道（§7.1/7.3，表 `channel`）：只提供改佣金率/停用/手动新增。
- 费用项：**不做管理页**；提供查询接口（§6 下拉/导入映射要用）即可。
- KV（§13.3/13.4 `app_setting`）读/写白名单 key。

**验收**：设置页四个卡片数据齐全可写；停用 1 间房 → `可售房间数` 相关接口随之变化（入住率/汇总使用处联动）；房号重复新增返回 40900。

## BE-03 成本线索（月度成本明细 + 汇总刷新）

**要做的**（03 §6，表 `monthly_cost`）
- POST 手录（`itemName` 会先匹配 `cost_item` 字典：命中带 `cost_item_id`，未命中 `cost_item_id=null` 并保留快照名；`type` 可覆盖字典默认）。
- GET 列表（按月 + 类型/来源过滤 + 分页）；PUT/DELETE 单行。
- GET 类型汇总（fixed/variable/one_time 三档合计 + 总数）+ GET 趋势（近 N 月三条线）。
- **每次增删改后 → 触发「月度重算」**（见 BE-06 的 RecalcService：刷新 `monthly_summary`）。

**验收**：2026-08 与 seed 一致（10 项 = 34500）；手录「电费」自动带上 cost_item_id=1；新增后将重算列正确刷入月度汇总与看板。

## BE-04 房态线索（具体房间 · 核心新模型）

**要做的**（03 §9，表 `daily_occupied_room` / `daily_occupancy` / `room`）
- `GET /api/occupancy/daily?month=`：每日聚合（含 `occupiedRoomNos`、是否周末/节假日、入住率）。
- `PUT /api/occupancy/day-rooms`：按 `bizDate + roomNos[]` **覆盖式**登记 → 事务内：清 `daily_occupied_room(biz_date)` 重写明细；未建档房号**自动建 room**；重算当日 `daily_occupancy`（`occupied_rooms`=明细计数，`total_rooms`=启用快照）。
- `GET /api/occupancy/day-rooms?bizDate=`：当日入住房号。
- `GET /api/occupancy/matrix?month=`：房间×日期矩阵（每 room 的 occupied 日期数组）。
- `POST /api/occupancy/batch`：多行逐日补录（同 day-rooms 语义）。
- 周末/节假日判定（含 2026 法定节假日表，简单内置即可）；工作日/周末拆分入住率 `workday-rate`。
- 写后异步刷新月度汇总 + 对账状态。

**验收**：2026-08 与 seed 完全一致（22 日合计 198）；把 8/22 的 101 取消 → 聚合/汇总/对账随之变化，再恢复一致；导入未建档房号（如 301）→ 自动建 room 且可售数+1。

## BE-05 渠道线索

**要做的**（03 §7，表 `channel_monthly` / `channel`）
- 列表（按月 join 渠道字典，口径到手价）+ 渠道×月综合（间夜/到手/挂牌/佣金/均价/占比）。
- 手录/修正某渠道本月数；佣金率优先取当行（渠道字典实时值亦可，以 03 为准）。
- 自动补算：`commission = gross - revenue`；`gross = revenue/(1-r)`（线上）；`avg_price = revenue/nights`；`revenue_month` 有则联动汇总。

**验收**：2026-08 六行与 seed 一致（合计 198/61960/挂牌 69037.89/佣金 7077.89）；改一行 → 汇总与看板联动。

## BE-06 月度汇总服务（RecalcService · 横切） + 利润页

**要做的**（03 §8，表 `monthly_summary`，§8.1/8.2 利润接口）
- 一个可幂等重算的服务：给定月份 → 由 `daily_occupancy`/`daily_occupied_room`/`channel_monthly`/`monthly_cost` 重算 `revenue/gross/commission/nights/adr/online_nights/offline_nights/occupancy_rate/total_cost/profit/data_status=computed`，UPSERT 进 `monthly_summary`。**成本/房态/渠道任何变动都调它**。
- 对账（§5.5 / §9.6）：`房态合计`（Σ occupied_rooms）vs `流水合计`（Σ channel_monthly.nights）→ `reconcile_status`：相等 `matched`，不等 `diff`（记 diff 值），无数据 `unchecked`；diff≠0 时给出「上报少/实测多」的渠道维排查信息。
- 利润页接口 + 同比（与前月）。

**验收**：2026-08 重算结果与 seed 的 `monthly_summary` 完全一致（含 reconcile=matched, diff=0）；人为造一个 diff 场景（比如把某渠道 +1 间夜）→ 看板与接口出现 diff 与排查信息；日常改数后看板数字始终正确（幂等）。

## BE-07 首页看板

**要做的**（03 §5：overview/trend/cost-structure/channel-ratio/reconcile）
- 组装一次性返回看板所需聚合（可走 monthly_summary + 明细查询，不必新表）。
- 环比/同比趋势供给前端；金额一律给字符串。

**验收**：2026-08 看板数字 = seed（61960/34500/27460/198/312.93/90.0%/matched diff 0）；7 月无数据时环比显示"—"。

## BE-08 导入编排（Excel 记账闭环 · 核心一期）

**要做的**（03 §12，表 `import_batch` 状态机，配合旁车 §14.1/14.2）
- 模板下载（§12.1）→ 上传（multipart `file`+`month`，建 batch=uploaded，暂存文件）→ 调旁车 `POST /api/parse` → 成功存结构化结果（可存临时表或 JSON，状态 mapped），失败标记 failed + 50300。
- 预览（§12.4）、映射（§12.5：把旁车归类建议转 `rawName→costItem/type/confidence`，未匹配的保留待人工）。
- 确认（§12.6）**事务落库**：
  1. 费用流水 → `monthly_cost`（item_name 快照 + cost_item_id 关联 + `import_batch_id`）；
  2. 每日房态（日期×房号）→ `daily_occupied_room` → 刷新 `daily_occupancy`；未建档房号自动建 `room`；
  3. 渠道销售 → `channel_monthly`；未匹配渠道名自动建 `channel`（线上默认佣金率取 `hotel_config.default_commission_rate`）；
  4. 高置信/用户确认的归类写 `import_mapping_rule`（is_manual=1 提高权重，供下次建议）；
  5. 调 RecalcService 重算 → 对账。
- 同月重复导入 → 40900（提供删除旧批次接口）。
- 旁车不可用 → 50100，前端降级「仍可手动归类」（主后端仍支持手动逐条添加入库，不依赖旁车）。

**验收**：拿 `月度记账模板.xlsx`（2026-08 数据）走通「上传→预览→确认」，落库后与 seed 对照全对、对账 matched；删旧批次后可重导；停掉 sidecar 上传 → 50100 与降级提示，手动模式可补齐。

---

## 二期 · 开工（BE-09 / BE-10）

> 接口：`03 §10` / `03 §11`；表：`db/schema.sql`（`pricing_suggestion` / `price_calc_scenario` / `prediction_result` / `breakeven_scenario` / `breakeven_cashflow` 已建 DDL）与 `02 §3.12–3.15`。
> 旁车对接：`03 §14.3 /api/predict`、`14.4 /api/llm/interpret`（沿用 `SidecarClient` 模式，错误映射 50100/50200/50300）。
> 金额一律 `BigDecimal` → JSON 字符串；日期 `LocalDate`。

## BE-09 定价 · 预测

### 09-A 档位 & 临近日建议价（§10.1–10.7）

**档位**：`PricingTierController`/`Service` 已建（BE-02），复核：
- `DELETE /api/pricing/tiers/{id}`：被 `pricing_suggestion.tier_id` 引用 → 该行 tier_id 置 NULL（历史不改价），不做物理删。

**建议价引擎（§10.6，核心）**：
- `POST /api/pricing/suggestions/generate` 入参 `{from, to}`（YYYY-MM-DD，跨度 ≤ 62 天校验，超限 40000）。
- 对区间每个 bizDate：
  1. 判 `isWeekend` / `isHoliday`（复用 BE-04 节假日判定）；
  2. 选档位：节假日→首个 `apply_days='holiday'` 且日期在 `effective_from~effective_to` 内、active 的档位；否则周末→`weekend`、平日→`weekday`（取 active、sort_order 最早的 base_price）；无匹配档位 → 返回该日 `basePrice=null` 并跳过（列表仍返回该日，`source` 标记无档位）；
  3. 预测入住率 `rate`：查 `prediction_result` 日粒度（该 bizDate，最近一版）→ 无则 null；
  4. **引擎系数表（v1，放常量/可配置，四舍五入到整元）**：
     `rate≥90 → ×1.06；85≤rate<90 → ×1.03；75≤rate<85 → ×1.00；60≤rate<75 → ×0.97；rate<60 → ×0.94；rate=null → ×1.00`；
  5. `suggestedPrice = round(base × 系数)`，`source='engine'`，`occupancy_forecast=rate`；
  6. 结果 **UPSERT `pricing_suggestion`（biz_date 唯一键）**：generated_at=now；若该日已有 `source='manual'` 行 → **跳过不覆盖**（手改价锁定优先），返回行 source 仍 'manual'。
- 响应 `{generated, items:[{bizDate, suggestedPrice, tierId, tierName, occupancyForecast, isWeekend, source}]}`。

**列表/手改（§10.5/10.7）**：
- `GET /api/pricing/suggestions?from&to`：按日期区间查 `pricing_suggestion` left join tier，返回同字段（未生成的日期不出现）。
- `PUT /api/pricing/suggestions/{bizDate}` 入参 `{suggestedPrice}`：UPSERT `tier_id` 保留原命中，`source='manual'`，校验 >0。

### 09-B 目标倒推（§10.8–10.10）

- `GET /api/pricing/calc/target`：`?targetRevenue&targetOccupancy&roomCount&daysPerMonth=`（缺省 `daysPerMonth` 取 `hotel_config.daysPerMonth`，默认 30.4）；
  - 校验：targetRevenue>0、0<targetOccupancy≤100、roomCount≥1、daysPerMonth>0；
  - `targetPrice = targetRevenue ÷ (roomCount × daysPerMonth × occupancy%)` → round(2)；`monthly.nights = roomCount × daysPerMonth × occ%`；`monthly.revenue=targetRevenue`、`adrNeed=targetPrice`。**纯计算不落库**。
- `POST /api/pricing/calc/scenarios` 存 `price_calc_scenario`（name 可空、存 targetRevenue/targetOccupancy/roomCount/resultPrice）；`GET /api/pricing/calc/scenarios` 返回最近 20 条（id 倒序）。

### 09-C 预测生成 + 落库 + 对接旁车（§10.11–10.13）

- `SidecarClient` 新增两方法（POST JSON、剥 `data` 信封）：
  - `predict(Map body)` → `14.3`，body 由主后端汇总；
  - `llmInterpret(Map body)` → `14.4`；
  - 均 3~6s 超时；旁车不可用抛 50100（沿用 catch 模式）。
- `POST /api/prediction/generate` 入参 `{month, metric}`（metric ∈ revenue/nights/occupancy_rate/adr/price，otherwise 40000）：
  1. 汇总历史：`monthly_summary` 近 12 个月该 metric 对应列（revenue→revenue、nights→nights、occupancy_rate→occupancy_rate、adr→adr、price→adr）得 `history:[{month,value}]`（空月跳过，不足 3 个月 → 40000「历史数据不足」）；
  2. `occupancyWindow`：近 30 日 `daily_occupancy` 的 `{date, rate}`；
  3. `economicHolidays`：BE-04 节假表命中目标月内的法定假日日期；`city`：`hotel_config.city`（可能为 null）；
  4. 调旁车 `predict`；
  5. 旁车成功：`predictedValue/engine("statistical"|"hybrid")/confidenceLow/High` → **调 `llmInterpret`**（body：`{metric, predictedValue, historyHeadline, ask}`，historyHeadline 由主后端拼「近 8 月收入 41200→61960，环比 +8%」式聚合字符串，**不含明细/身份字段**）→ 得 llm_interpretation（llmAvailable=false 或失败 → null，不阻塞）；
  6. **落库 `prediction_result`**（target_type='monthly', target=month, metric, engine, confidence_low/high, llm_interpretation, model_version 旁车给或置 null, generated_at=now）→ 返回统一 `data`（同 §10.11 响应）。
  7. **旁车挂/超时降级**：纯统计兜底（主后端内置：近 3 月加权均值 × 目标月节假日系数[命中节假日 ≥2 天 ×1.05，否则 ×1.0]），`engine='statistical'`、confidenceLow/High 按 ±10% 拼、llmInterpretation=null → **仍 200**（响应 `data.degraded=true` 标记），前端给降级提示。
- `GET /api/prediction/results?target=YYYY-MM&metric=`：查 `prediction_result`（target 必填，单独或最近）；返回多条历史（目标月降序）。
- `GET /api/prediction/daily?date=&month=`（§10.13，给建议价引擎用）：
  - 查 `prediction_result` 日粒度已有缓存 → 返回；无 → 用 `occupancyWindow` 前 30 日逐日 `occupancy_rate` 移动平均 + 周末系数（周末 +5pp）现算一个 `{date, rate}`，缓存进 `prediction_result`（target_type='daily'）并按月返回；month 缺省 = 今天所在月。

**BE-09 验收**（2026-08/09 seed 锚点）：
- 档位：`GET /api/pricing/tiers` = 平日价 260 / 周末价 360 / 节假日价 520；
- 目标倒推 `?targetRevenue=80000&targetOccupancy=85&roomCount=10` → 309.60；
- `from=2026-08-23&to=2026-09-05` generate → 14 行；8/23(日) 周末价 360×1.0、9/30–10/7 节假日价 520（周内也要命中 holiday，因为 `effective_from/to` 覆盖）；已有 manual 行不被覆盖；重复 generate 幂等（同值）；
- 预测 generate 2026-09 revenue：落 `prediction_result` 1 行、`confidenceLow < predictedValue < confidenceHigh`；断旁车（停 sidecar）→ 200 + degraded=true + 纯统计兜底，不 500；
- 预测失败/数据不足（如历史 <3 月）→ 40000 合理文案。

## BE-10 回本测算（§11）

- **月供（等额本息）**：`payment = LOAN × (RATE/12) ÷ (1 − (1 + RATE/12)^(−YEARS×12))` —— BigDecimal、`Math.pow(1 + RATE/12, −YEARS×12)`、round(2)。
- **校验（POST/PUT）**：investment>0；0 ≤ ownCapital ≤ investment；loanAmount = investment − ownCapital（若不传自算，传了校验一致，不一致 40000）；0<loanRate≤0.40；1≤loanYears≤40；monthlyNetInflow ≥ 0。
- **现金流生成（按 scenario 参数）**：
  - `monthly_payment` 先算好存 `breakeven_scenario`；
  - `breakeven_cashflow` 逐月：`start_balance = −loanAmount`（§11.5 示例一致：month1 runningBalance = −1200000 + 7964.32 = −1192035.68）；`net = monthlyNetInflow − payment`；`running_balance += net`；
  - 生成到 **running_balance 首次 ≥ 0**（该月即 `breakEvenMonth` = monthSeq）或上限 **360 月**（超过 → breakEvenMonth=null「未回本」）；
  - `breakEvenDate = 起始月(2026-08 + monthSeq)` 格式 YYYY-MM（**2026-08-24 二期修订：`+monthSeq`，勿再减 1**。month1=2026-08，所以 151 → 2039-03；03 §11.5 旧例 58→2031-06 同为 +monthSeq）。
- `POST /api/breakeven/scenarios`（11.2）：校验 → 算 payment → 生成 cashflow → **同一事务**写两表 → 返回 `{id, monthlyPayment, breakEvenMonth, breakEvenDate}`。
- `PUT /api/breakeven/scenarios/{id}`（11.3）：改任意参 → 删旧 cashflow 重建（事务）+ 重算 payment/breakEven；`DELETE`（11.4）直接删（cashflow FK CASCADE）。
- `GET /api/breakeven/scenarios`（11.1）：列表（含 monthlyPayment/breakEvenMonth）。
- `GET /api/breakeven/scenarios/{id}/cashflow`（11.5）：`{scenario:{id,name,monthlyPayment,breakEvenMonth,breakEvenDate}, rows:[{monthSeq,inflow,outflow,net,runningBalance,remark}]}`（rows ≤ 360；remark 回本当月填「回本」）。
- `GET /api/breakeven/scenarios/{id}/sensitivity`（11.6）：
  - 三个维度各取 5 点：`factor ∈ {0.8, 0.9, 1.0, 1.1, 1.2}`；
  - **每次只动一个维度**、其余保持基准，例如：
    - 月净流入：`net = monthlyNetInflow×factor − payment`；
    - 月供：`payment' = payment×factor`，`net = monthlyNetInflow − payment'`；
    - 投资额：`loan' = (investment×factor) − own_capital`（下限 0），起点 `−loan'`，payment 不变；
  - 重算回本月（同为「累计起始 −loan → 首次 ≥0」口径；上限 360 → null）→ 返回：
    `{ base:{axis:"月净流入", factor:1.0, breakEvenMonth}, rows:[{axis:"月净流入", factor:0.8, breakEvenMonth}, … 共 15 行] }`（前端渲染 3×5 网格，base 行 factor=1.0）。

**BE-10 验收**（seed 锚点）：
- 用 seed 参数 POST → `monthlyPayment=12035.68`、`breakEvenMonth=151`、`breakEvenDate=2039-03`（docs §11.5 的 58/2031-06 是旧占位示例，**已由 03 §11.5 批注更正为以本工单为准**）；
- cashflow：151 行，第 151 行 runningBalance ≥0、150 行 <0；month1 runningBalance = −1,195,035.68；
- PUT `monthlyNetInflow=30000` → breakEvenMonth=67（⌈1200000/17964.32⌉）；再 PUT 还原；
- sensitivity 15 行、base(factor=1.0)=151、月净流入 0.8 → ⌈1200000/(16000−12035.68)⌉=⌈302.7⌉=303 行内数字合理（≥ base）；
- 非法（loanRate>0.40 / loanAmount+own≠investment）→ 40000。

## 一期整体验收（对应 05 §4 验收标准）

- [ ] 月度 Excel 进来看板全对（BE-08 + BE-06 + BE-07 闭环）；
- [ ] 对账能抓差房（BE-06 对账语义 + BE-07 看板 diff）；
- [ ] 具体房间房态模型完整（BE-04），设置页房间管理可用（BE-02）；
- [ ] 旁车故障降级链路通（50100 + 手动归类）。
