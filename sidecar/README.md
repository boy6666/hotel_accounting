# Python 旁车（FastAPI）—— SC-01 ~ SC-05

酒店记账系统的独立旁车服务：Excel 解析（SC-02）+ 智能归类建议（SC-03）
+ 时序预测（SC-04）+ DeepSeek 解读（SC-05）。

## 边界（docs/05 §3）
- 仅监听 `127.0.0.1:8001`、无 JWT、**只被 Java 主后端调用**（docs/03 §14）。
- **无状态、不碰数据库**：解析/归类/预测/LLM 都只是算法输出；学习规则由主后端在 confirm 时写 `import_mapping_rule`。
- LLM 只送聚合摘要（红线：不送身份证类/政治/安全敏感字段、完整个人数据）；key 从环境变量读、不落库。

## 启动

```bash
pip install -r requirements.txt          # fastapi / uvicorn / openpyxl / pydantic
uvicorn app:app --host 127.0.0.1 --port 8001
```

健康检查（主后端轮询）：

```bash
curl http://127.0.0.1:8001/api/health
# {"code":0,"message":"ok","data":{"status":"up","version":"1.0.0"}}
```

## 接口

| 接口 | 说明 |
|---|---|
| `GET  /api/health` | 健康检查，信封 `{code:0,message,data:{status,version}}` |
| `POST /api/parse` | `{file_path, month, template_type?}` → 结构化三表 JSON + 归类建议；坏/缺文件返 `50300` 带行号 |
| `POST /api/categorize` | `{rawNames[], costItems?[]}` → 每行 `{rawName, suggestCostItemId, suggestType, confidence, matched}`；引擎 `similarity-v1`，无状态 |
| `POST /api/predict` | SC-04 `{target,metric,history[],occupancyWindow?,city?,economicHolidays?}` → `{predictedValue,confidenceLow,confidenceHigh,engine:"statistical",modelVersion:"v1",weatherUsed,degraded}` |
| `POST /api/llm/interpret` | SC-05 `{metric,predictedValue,historyHeadline,ask?}` → `{llmAvailable,interpretation}`；三态都 code=0，无 key/上游异常降级不抛错 |

统一信封 `{code, message, data}`；错误码对齐 docs/03 §3：`40000`(参数) / `50000`(内部) / `50300`(解析失败，HTTP 422)。

## 解析布局（每日房态自适应）
- `room_type_count`：模板旧版「房型A-D × 日计数」→ `{bizDate, roomNos:[], occupiedCount}`（无具体房号，主后端按日聚合落库）；
- `date_room`：日期 × 房号（独立『房号』列或房号列）→ `{bizDate, roomNos[]}`；
- `sparse_days`：稀疏天数列（行=房号、列=1..31 天）→ 多天同房号集合。

## 测试

```bash
python -m pytest -q      # 36 用例：一期解析/归类 + SC-04 预测 + SC-05 LLM（三态+红线）
```

测试盘（`tests/fixtures/`）由 `tests/make_fixtures.py` 按 seed.sql 的 2026-08 数据生成：
成本 10 行 34500 / 房态 22 日 198 间夜 / 销售 6 渠道 198 间夜、收入 61960。
SC-04 用锚定 2026-08 的 12 个月合成历史（revenue 61960 → 目标 2026-09）；天气网络一律 mock，不碰真实外网。

## 目录
```
sidecar/
  app.py                  # FastAPI 入口 + 异常兜底
  core/errors.py          # 信封/错误码
  core/logging_conf.py    # logs/sidecar-<date>.log 保留 14 天
  core/utils.py           # Sheet 名兼容 / 金额/日期解析
  services/excel_parser.py# SC-02 解析
  services/categorizer.py # SC-03 归类（difflib 相似度）
  services/predictor.py   # SC-04 统计预测（WMA+节假日+入住率+天气降级+置信区间）
  services/llm.py         # SC-05 DeepSeek 解读（三态降级，红线白名单）
  tests/                  # pytest + 测试盘生成
```
