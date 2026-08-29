# -*- coding: utf-8 -*-
"""酒店记账系统 · Python 旁车（FastAPI）—— SC-01~SC-05

边界（docs/05 §3）：仅监听 127.0.0.1:8001、无 JWT、只被主后端调用；对外不可达。
无状态、不碰数据库。SC-01~SC-03 一期；SC-04 预测 / SC-05 LLM 二期。

启动：uvicorn app:app --host 127.0.0.1 --port 8001
"""
import re

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field

from core.errors import ApiError, err as err_body
from core.logging_conf import logger
from services.categorizer import categorize_names
from services.excel_parser import parse_workbook as do_parse
from services.llm import interpret as run_interpret
from services.predictor import predict as run_predict

APP_NAME = "hotel-accounting-sidecar"
VERSION = "1.1.0"

app = FastAPI(title="酒店记账 · Python 旁车（解析/归类/预测/LLM解读）", version=VERSION,
              docs_url="/api/docs", openapi_url="/api/openapi.json")

_MONTH_RE = re.compile(r"^\d{4}-(0[1-9]|1[0-2])$")


# ------------------------------------------------------------------- 请求模型 ---

class ParseRequest(BaseModel):
    file_path: str = Field(..., description="主后端已落盘的 .xlsx 绝对路径")
    month: str = Field(..., description="YYYY-MM")
    template_type: str | None = Field(default=None, description="模板类型（预留，默认月度模板）")


class CostItemRef(BaseModel):
    id: int | None = None
    name: str
    defaultType: str = "variable"


class CategorizeRequest(BaseModel):
    rawNames: list[str] = Field(..., min_length=1, description="本月费用行的原始名称数组")
    costItems: list[CostItemRef] | None = Field(default=None, description="已有 cost_item 字典名列表（可选）")


class HistoryPoint(BaseModel):
    model_config = ConfigDict(extra="ignore")
    month: str = Field(..., description="YYYY-MM，时间升序")
    value: float = Field(..., description="该月该指标汇总值（非负）")


class OccupancyWindowPoint(BaseModel):
    model_config = ConfigDict(extra="ignore")
    date: str = Field(..., description="YYYY-MM-DD")
    rate: float = Field(..., description="当日入住率 0-100")


class PredictRequest(BaseModel):
    model_config = ConfigDict(extra="ignore")
    target: str = Field(..., description="目标月 YYYY-MM")
    metric: str = Field(..., description="revenue|nights|occupancy_rate|adr|price")
    history: list[HistoryPoint] = Field(..., description="该指标历史月汇总（≥3 条）")
    occupancyWindow: list[OccupancyWindowPoint] | None = Field(default=None, description="近 30 日入住率（可空）")
    city: str | None = Field(default=None, description="hotel_config.city，天气可空降级")
    economicHolidays: list[str] = Field(default_factory=list, description="节假日日期 YYYY-MM-DD")


class InterpretRequest(BaseModel):
    model_config = ConfigDict(extra="ignore")  # 红线：忽略 idCard/name 等额外字段，不入上下文
    metric: str = Field(..., description="指标名（聚合摘要）")
    predictedValue: float = Field(..., description="聚合预测值")
    historyHeadline: str = Field(..., description="历史摘要字符串（旁车不接触明细）")
    ask: str | None = Field(default=None, description="解读指令，默认给出定价/经营建议")


# ------------------------------------------------------------------- 异常兜底 ---

@app.exception_handler(ApiError)
async def api_error_handler(_: Request, exc: ApiError):
    logger.warning("ApiError code=%s msg=%s", exc.code, exc.message)
    return JSONResponse(
        status_code=exc.http_status,
        content={"code": exc.code, "message": exc.message, "data": None},
    )


@app.exception_handler(Exception)
async def unhandled_error_handler(_: Request, exc: Exception):
    logger.exception("unhandled exception: %s", exc)
    return JSONResponse(
        status_code=500,
        content={"code": 50000, "message": f"旁车内部异常：{exc}", "data": None},
    )


# ------------------------------------------------------------------- 接口 ---

@app.get("/api/health")
def health():
    """健康检查（主后端轮询，故障降级 → 50100）。"""
    return {"code": 0, "message": "ok",
            "data": {"status": "up", "version": VERSION}}


@app.post("/api/parse")
def parse(req: ParseRequest):
    """SC-02 解析 Excel → 结构化三表 JSON（纯解析，不落库）。"""
    if not _MONTH_RE.match(req.month):
        raise ApiError(40000, f"month 格式应为 YYYY-MM：{req.month}", 400)
    data = do_parse(req.file_path, req.month, req.template_type)
    return {"code": 0, "message": "ok", "data": data}


@app.post("/api/categorize")
def categorize(req: CategorizeRequest):
    """SC-03 批量智能归类建议（字符串相似度，一期不上模型）。无状态。"""
    dict_items = None
    if req.costItems is not None:
        dict_items = [
            {"id": it.id, "name": it.name, "defaultType": it.defaultType}
            for it in req.costItems
        ]
    data = categorize_names(req.rawNames, dict_items)
    return {"code": 0, "message": "ok", "data": data}


@app.post("/api/predict")
def predict(req: PredictRequest):
    """SC-04 时序预测（统计方法：日历+节假日+天气[city 可空降级]）→ 预测值+置信区间。"""
    data = run_predict(
        target=req.target,
        metric=req.metric,
        history=[{"month": h.month, "value": h.value} for h in req.history],
        occupancy_window=[{"date": o.date, "rate": o.rate} for o in (req.occupancyWindow or [])]
        if req.occupancyWindow else None,
        city=req.city,
        economic_holidays=req.economicHolidays or [],
    )
    return {"code": 0, "message": "ok", "data": data}


@app.post("/api/llm/interpret")
def llm_interpret(req: InterpretRequest):
    """SC-05 DeepSeek 解读（只送聚合摘要）。三态都 code=0，主后端不同分支。"""
    data = run_interpret({
        "metric": req.metric,
        "predictedValue": req.predictedValue,
        "historyHeadline": req.historyHeadline,
        "ask": req.ask,
    })
    return {"code": 0, "message": "ok", "data": data}
