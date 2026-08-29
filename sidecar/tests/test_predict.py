# -*- coding: utf-8 -*-
"""SC-04 /api/predict 测试：统计预测 + 置信区间 + 节假日 + 天气降级 + 校验。"""
from fastapi.testclient import TestClient

from app import app
from services import predictor

client = TestClient(app)

# 12 个月 demo 历史（锚定 db/seed.sql 2026-08：revenue 61960 / nights 198 / adr 312.93 / occ 90.00）。
# 目标月一律取 2026-09（晚于最后一条 2026-08）。
MONTHS = ["2025-09", "2025-10", "2025-11", "2025-12",
          "2026-01", "2026-02", "2026-03", "2026-04",
          "2026-05", "2026-06", "2026-07", "2026-08"]
REVENUE = [32600, 34900, 33200, 35600, 38200, 35800,
           40400, 43600, 46800, 50100, 54800, 61960]
NIGHTS = [118, 124, 119, 128, 138, 133, 156, 168, 176, 181, 190, 198]
ADR = [276, 281, 279, 278, 277, 269, 259, 260, 266, 277, 288, 312.93]
OCC = [74, 76, 73, 77, 80, 78, 84, 86, 88, 88.5, 89.5, 90.0]

HOLIDAY_5 = ["2026-09-16", "2026-09-17", "2026-09-18", "2026-09-19", "2026-09-20"]


def history_for(values, months=MONTHS):
    return [{"month": m, "value": v} for m, v in zip(months, values)]


def occ_window():
    """近 30 日入住率，均值恰为 90。"""
    return [{"date": f"2026-08-{d:02d}", "rate": 90 - 2 + (d % 5)} for d in range(1, 31)]


def _predict(**kw):
    resp = client.post("/api/predict", json=kw)
    assert resp.status_code == 200, resp.text
    return resp.json()["data"]


def test_predict_revenue_baseline():
    data = _predict(metric="revenue", history=history_for(REVENUE), target="2026-09",
                    occupancyWindow=occ_window(), city=None)
    assert data["engine"] == "statistical"
    assert data["modelVersion"] == "v1"
    assert data["method"] == "wma+holiday+weather(optional)"
    # 置信区间恒 low < point < high
    assert data["confidenceLow"] < data["predictedValue"] < data["confidenceHigh"]
    # 落点近 3 月区间内合理
    last3 = REVENUE[-3:]
    assert min(last3) * 0.9 <= data["predictedValue"] <= max(last3) * 1.3
    # city=null → 天气跳过（契约：可空降级）
    assert data["weatherUsed"] is False
    assert data["degraded"] is True


def test_predict_holidays_raise_revenue():
    base = _predict(metric="revenue", history=history_for(REVENUE), target="2026-09",
                    city=None)["predictedValue"]
    with_holiday = _predict(metric="revenue", history=history_for(REVENUE), target="2026-09",
                            city=None, economicHolidays=HOLIDAY_5)["predictedValue"]
    assert with_holiday >= base * 1.09          # 5 天 → ×1.10


def test_predict_holidays_occupancy_pp():
    base = _predict(metric="occupancy_rate", history=history_for(OCC), target="2026-09",
                    city=None)["predictedValue"]
    with_holiday = _predict(metric="occupancy_rate", history=history_for(OCC), target="2026-09",
                            city=None, economicHolidays=HOLIDAY_5)["predictedValue"]
    # 5 天 → +10pp，封顶 100（基线接近 90，会砍到 100）
    assert abs(with_holiday - round(min(base + 10.0, 100.0), 2)) < 0.02


def test_predict_holidays_occupancy_pp_not_clamped():
    """基线离 100 远时 +10pp 不封顶。"""
    low_occ = [64, 66, 63, 67, 70, 68, 74, 76, 78, 78.5, 79.5, 80.0]
    base = _predict(metric="occupancy_rate", history=history_for(low_occ), target="2026-09",
                    city=None)["predictedValue"]
    with_holiday = _predict(metric="occupancy_rate", history=history_for(low_occ), target="2026-09",
                            city=None, economicHolidays=HOLIDAY_5)["predictedValue"]
    assert with_holiday - base >= 9.9           # +10pp 生效


def test_predict_weather_degraded_equals_pure_calendar(monkeypatch):
    def boom(_city):
        raise RuntimeError("network down")
    monkeypatch.setattr(predictor, "fetch_climate", boom)

    pure = _predict(metric="revenue", history=history_for(REVENUE), target="2026-09", city=None)
    degraded = _predict(metric="revenue", history=history_for(REVENUE), target="2026-09",
                        city="杭州")
    assert degraded["weatherUsed"] is False
    assert degraded["degraded"] is True
    assert degraded["predictedValue"] == pure["predictedValue"]
    assert any("degraded" in log for log in degraded["logs"])


def test_predict_weather_used(monkeypatch):
    def sunny(_city):
        return {"source": "open-meteo", "rain_prob": 5.0}
    monkeypatch.setattr(predictor, "fetch_climate", sunny)
    data = _predict(metric="revenue", history=history_for(REVENUE), target="2026-09",
                    city="杭州")
    assert data["weatherUsed"] is True
    assert data["degraded"] is False


def test_predict_occupancy_rate_window_smoothing():
    win_avg = 90.0
    hist_mean = sum(OCC) / len(OCC)
    expected = 0.3 * win_avg + 0.7 * hist_mean
    data = _predict(metric="occupancy_rate", history=history_for(OCC), target="2026-09",
                    occupancyWindow=occ_window(), city=None)
    assert abs(data["predictedValue"] - round(expected, 2)) < 0.01


def test_predict_confidence_fallback_8pct():
    """历史 <6 月 → 置信区间 ±8%。"""
    hist = history_for([40000.0, 43000.0, 46000.0])
    data = _predict(metric="revenue", history=hist, target="2026-09", city=None)
    p = data["predictedValue"]
    assert abs((data["confidenceHigh"] - p) - p * 0.08) < 0.05
    assert abs((p - data["confidenceLow"]) - p * 0.08) < 0.05
    assert data["confidenceLow"] < p < data["confidenceHigh"]


def test_predict_confidence_backtest_floor():
    """≥6 月历史 → 回测 MAE 决定半径（不低于 point×5% 下限）。"""
    data = _predict(metric="revenue", history=history_for(REVENUE), target="2026-09", city=None)
    p = data["predictedValue"]
    high_margin = data["confidenceHigh"] - p
    low_margin = p - data["confidenceLow"]
    assert high_margin >= p * 0.05
    assert low_margin >= p * 0.05
    assert data["confidenceLow"] < p < data["confidenceHigh"]


# ------------------------------------------------------------------ 校验 ---

def test_predict_history_lt_3():
    resp = client.post("/api/predict", json={
        "target": "2026-09", "metric": "revenue",
        "history": [{"month": "2026-01", "value": 1}, {"month": "2026-02", "value": 2}],
    })
    assert resp.status_code == 400
    body = resp.json()
    assert body["code"] == 40000
    assert "至少需要 3 条" in body["message"]


def test_predict_invalid_metric():
    resp = client.post("/api/predict", json={
        "target": "2026-09", "metric": "bogus", "history": history_for(REVENUE),
    })
    assert resp.status_code == 400
    assert resp.json()["code"] == 40000


def test_predict_target_must_be_later():
    resp = client.post("/api/predict", json={
        "target": "2026-08", "metric": "revenue", "history": history_for(REVENUE),
    })
    assert resp.status_code == 400
    assert resp.json()["code"] == 40000
    assert "必须晚于" in resp.json()["message"]


def test_predict_dup_month_rejected():
    resp = client.post("/api/predict", json={
        "target": "2026-09", "metric": "revenue",
        "history": [{"month": "2026-01", "value": 1},
                    {"month": "2026-01", "value": 2},
                    {"month": "2026-02", "value": 3}],
    })
    assert resp.status_code == 400
    assert "重复月份" in resp.json()["message"]
