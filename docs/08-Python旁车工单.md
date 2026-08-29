# Python 旁车（FastAPI）开发工单 —— 一期

> **接口契约**：`03-API文档.md §14`（主后端 → 旁车，仅 127.0.0.1，无 JWT）。
> 目录：`sidecar/`。公共契约先读 `05-开发工单总览.md` §2 与 §3（边界：旁车一期**无状态、不碰数据库**）。
> 一期范围：SC-01 ~ SC-03；SC-04/05 为【二期】。

## 技术选型（建议，可替换）

- Python 3.10+ / FastAPI + uvicorn；openpyxl 解析 xlsx；pydantic 出参。
- 只监听 `127.0.0.1:8001`；所有接口 `POST`（除 `/health` GET）；返回**与主后端一致的信封** `{code,message,data}`；内部差错以 `code/message` 返回，由主后端统一映射 50100/50200/50300。

## SC-01 骨架 + 健康检查

**要做的**
- FastAPI 应用；`GET /api/health` → `{"code":0,"message":"ok","data":{"status":"up","version":"1.0.0"}}`。
- 异常兜底：解析/归类内部异常统一转 5xx 信封，不裸抛；日志 `logs/sidecar-%date.log` 保留 14 天。
- 通用工具：读取 Excel 的 sheet 名兼容（模板三 Sheet 中文名可含空格/「每日房态」别名容忍）。

**验收**：`uvicorn app:app --host 127.0.0.1 --port 8001` 起服务；主后端能轮询到 `/health`。

## SC-02 解析 Excel（`POST /api/parse`）—— 一期核心

**要做的**（对齐 03 §12 / §14.1；输入主后端传 `file_path` + `month` + `template_type` 或整体月）
- openpyxl 读三 Sheet 产出**结构化 JSON**：
  - **费用流水**：`[{ rowNo, rawName, amount, type(raw 中文→归类建议)，note }]`；
  - **每日房态**：按「日期/房号」解析为 `[{ bizDate, roomNos:[...] }]`（对模板「天数列」兼容：若一行是多个天数列 = 多天同房号集合）；未建档房号只透传，建档由主后端确认时做；
  - **渠道销售**：`[{ rawName(渠道名), nights, revenue, grossRevenue?, commission? }]`（缺则主后端按佣金口径补算）；
  - 摘要：`rawNameSummary`（"8月：电费/水费/携程/美团…"）。
- 解析失败：返回 `code=50300` + 具体行号/原因（03 §3）。
- 返回给主后端暂存，**不落库**。

**验收**：用 `generate_monthly_template.py` 生成的模板（2026-08 数据）解析结果与 seed 三方对齐（成本 10 行 34500 / 房态 22 日 198 / 销售 6 渠道 198 间夜）；含乱行/空行/备注列能容错；坏文件返回 50300 且带行号。

## SC-03 智能归类建议（`POST /api/categorize`）—— 一期核心

**要做的**（对齐 03 §14.2 / §12.5）
- 输入：本月费用行的原始名称数组（+可选已有 `cost_item` 字典名列表）。
- 输出：每行 `{ rawName, suggestCostItemId, suggestType, confidence, matched }`。
  - 规则（一期可先用经典方法）：精确/包含匹配字典名 → 高置信（≥0.9）取 `default_type`；拼音/近义/切词相似 → 中置信提出候选；完全不认识 → `matched=false` 低置信建议 `variable`。
  - 用字符串相似度（difflib/Jaro 均可）即可，**一期不上模型**；预留 `engine` 字段便于二期替换。
- 无状态：学习规则 `import_mapping_rule` 由主后端在 confirm 时持久化并在下次把高置信规则回传（本服务不存）。

**验收**：输入 seed 的 10 个费用原名，匹配 cost_item 字典 ≥8 项高置信；「刷单」等 one_time 词给出类型建议；未知名给低置信+默认类型。

---

## 二期 · 开工（SC-04 / SC-05）

> 接口：`03 §14.3 /api/predict`、`14.4 /api/llm/interpret`。仍**无状态、不碰数据库、仅 127.0.0.1:8001**；信封 `{code,message,data}`。
> 新增路由注册到 `app.py`；模型类用 pydantic；错误用 `core.errors.ApiError`（50300 带原因）。
> **红线（逐字保留）**：向 DeepSeek 出网只送聚合数字与费用名摘要；**不送**任何身份证类、政治/安全敏感字段、完整个人数据。

## SC-04 时序预测（`POST /api/predict`）

**请求**（主后端已汇总，不含明细行）：

```jsonc
{ "target": "2026-09", "metric": "revenue",
  "history": [ { "month": "2026-01", "value": 41200.0 }, ... ],
  "occupancyWindow": [ { "date": "2026-08-20", "rate": 90.0 } ],
  "city": "杭州", "economicHolidays": ["2026-09-16", ...] }
```

- 校验：`metric ∈ {revenue,nights,occupancy_rate,adr,price}`；`history` 非空且 ≥3 条；`target > 最后一条 history.month`；`occupancyWindow` 可空；`city` 可空。违反 → `ApiError(40000, 原因)`。
- **统计方法（可解释、无训练）**：
  1. **趋势基线**：近 3 月加权平均（权重 0.5/0.3/0.2）+ 近 3 月平均环比（`(v_i − v_{i-1})/v_{i-1}` 均值，正负都保留）外推一个点；
  2. **节假日修正**：目标月内以 `economicHolidays` 天数为节气强度 `d`，系数 `1 + min(d×0.02, 0.10)`（2 天→+4%，5 天+10% 封顶）；对 occupancy_rate 直接加 pp 数（`min(d×2, 10)` pp）再按“入住率 → 收入”传导折算；
  3. **周末/日历结构**：`occupancyWindow` 近 30 日率为基准比（有窗口时以窗口当前水平加权 0.3、历史月均值 0.7 平滑）；无窗口则纯历史；
  4. **天气（可降级）**：`city` 非空时尝试免费天气接口（如 wttr.in / open-meteo，按城市聚合当月气候特征/异常天气提示），超时或失败**不报错**，`logs` 标注 `weather degraded`，继续纯日历+节假日；`city` 为空直接跳过（契约：可空降级）。
- **confidence interval**：用近 6 月回测残差（同法对每月回测的绝对误差）取 MAE → 区间 = `point ± max(MAE×1.64, point×0.05)`；无 6 月历史则 ±8%；校验 `low < point < high` 恒成立。
- **输出 data**：
  ```jsonc
  { "target": "2026-09", "metric": "revenue",
    "predictedValue": 74500.0, "confidenceLow": 68200.0, "confidenceHigh": 80800.0,
    "engine": "statistical", "modelVersion": "v1", "method": "wma+holiday+weather(optional)",
    "weatherUsed": false, "degraded": true }
  ```
- **失败路径**：输入错 → 40000/50300（带原因）；内部异常 → 50000 信封。

**SC-04 验收**：
- 用 seed 的 `monthly_summary` 历史（2026-08 等）合成请求 → 2026-09 预测值落在「近 3 月区间内合理」（有置信区间且 low<point<high）；`economicHolidays` 给 5 天 → 值 ≥ 无节假日的同参；
- `city=null` / 天气接口挂 → 仍 200、`weatherUsed=false` 且结果与纯日历一致；
- 历史 <3 条 → 40000。

## SC-05 LLM 解读（`POST /api/llm/interpret`）

**请求**（聚合摘要字符串，旁车不接触明细）：

```jsonc
{ "metric": "revenue", "predictedValue": 74500.0,
  "historyHeadline": "近8月收入 41200→61960，环比 +8%",
  "ask": "解读趋势并给出定价/经营建议，200字内" }
```

- 实现：调 **DeepSeek Chat Completions**（`https://api.deepseek.com/chat/completions`，model 默认 `deepseek-chat`）。
  - **API key 从环境变量读**：`DEEPSEEK_API_KEY`（可选 `DEEPSEEK_BASE_URL` / `DEEPSEEK_MODEL` 覆盖）；**不落库**（03 §13.2 KV 提示）。
  - system prompt：仅基于所给数字与费用名摘要作答；≤200 字；不索要/不输出任何个人、政治、安全敏感内容。
- **三态（都是 code=0，主后端不同分支）**：
  - 未配置 key → `{ llmAvailable: false, interpretation: null }`（不调网，直接返回）；
  - 调用成功 → `{ llmAvailable: true, interpretation: "…" }`；
  - 上游超时/4xx/5xx/JSON 异常 → **降级**：`{ llmAvailable: false, interpretation: null }`（记录 warning；不抛错，主后端纯统计兜底走通）。
- 依赖用 `requests`（或 httpx）；DeepSeek 相关逻辑放 `services/llm.py`，网络层可 mock。

**SC-05 验收**：
- 无 `DEEPSEEK_API_KEY` → 返回 `llmAvailable:false` 且毫秒级（未发起网络）；
- mock `requests.post` 成功 → 返回 interpretation；mock 500/超时 → 降级 `llmAvailable:false` 不抛错；
- 断言发送 body 只含聚合字段（`historyHeadline`/`predictedValue`/`metric`），**不含任何可识别个体字段**（测试里放一条假的 `idCard`/`name` 证明不会外传）。

## 一期整体验收

- [ ] `/api/parse` 三表解析与 seed 全对；错误带行号；
- [ ] `/api/categorize` 高置信建议可靠、完全未知名可人工兜底；
- [ ] `/health` 可被主后端轮询；旁车挂掉 → 主后端 50100 降级路径通。
