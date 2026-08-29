# -*- coding: utf-8 -*-
"""SC-04 时序预测（统计方法，可解释、无训练）。

对齐 docs/08 §SC-04 与 docs/03 §14.3：
  校验 metric/history/target → 趋势基线(WMA+平均环比外推) → 节假日修正 → 入住率平滑
  → 天气(按 hotel_config.city 可空降级，免费接口) → 置信区间（近 6 月回测 MAE）。

无状态、不碰数据库。输入由主后端汇总提交（不含明细行）。

方法口径（同时写入响应 method/logs 字段）：
  - 趋势基线：近 3 月加权平均（权重 0.5/0.3/0.2）+ 近 3 月平均环比外推一个点；
  - 节假日：目标月 economicHolidays 天数 d → 一般指标 ×(1 + min(d×0.02, 0.10))；
    occupancy_rate 直接加 min(d×2, 10) 个百分点（入住率 → 收入即内含于点位传导）；
  - 入住率：occupancyWindow 近 30 日率 加权 0.3、历史月均值 0.7 平滑（occupancy_rate
    指标直接以该值作点位；其余指标最近端需求已隐含在自身序列中，窗口仅作已校验输入，
    不二次传导）；无窗口则纯历史趋势；
  - 天气：city 非空时尝试 wttr.in → 回退 open-meteo，超时/失败不报错，logs 标
    degraded，继续纯日历+节假日；city 为空直接跳过（契约：可空降级）；
  - 置信区间：≥6 月历史 → 近 6 月回测残差 MAE×1.64（下限 point×5%）；<6 月 → ±8%；
    恒有 low < point < high。
"""
import logging
import re
from statistics import mean
from urllib.parse import quote

import requests

from core.errors import ApiError

logger = logging.getLogger("sidecar.predictor")

ENGINE = "statistical"
MODEL_VERSION = "v1"
METRICS = {"revenue", "nights", "occupancy_rate", "adr", "price"}

_MONTH_RE = re.compile(r"^\d{4}-(0[1-9]|1[0-2])$")
_DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")

# 趋势基线（近 → 远）
TREND_WEIGHTS = (0.5, 0.3, 0.2)
MAX_MOM_SPAN = 3  # 平均环比覆盖最近 3 段

# 节假日
HOLIDAY_RATE_PER_DAY = 0.02      # 一般指标每天 +2%
HOLIDAY_RATE_CAP = 0.10          # 封顶 +10%
HOLIDAY_OCC_PP_PER_DAY = 2.0     # occupancy_rate 每天 +2 个百分点
HOLIDAY_OCC_PP_CAP = 10.0        # 封顶 +10pp

# 入住率平滑
OCC_WINDOW_W = 0.3
OCC_HIST_W = 0.7

# 天气
WEATHER_TIMEOUT = 4.0            # 秒；失败快速降级
WEATHER_ADJ_CAP = 0.05           # 天气对点位影响封顶 ±5%

# 置信区间
BACKTEST_MONTHS = 6              # 回测窗口月数
BACKTEST_FALLBACK_R = 0.08       # 历史 <6 月 → ±8%
CONF_FLOOR_R = 0.05              # 区间半径下限 = point×5%
CONF_MAE_K = 1.64


# ------------------------------------------------------------------- 校验 ---

def validate_input(metric, history, target, occupancy_window):
    """契约 §14.3 校验；违反 → ApiError(40000, 原因, http 400)。"""
    if metric not in METRICS:
        raise ApiError(40000, f"metric 不合法：{metric}，应为 {sorted(METRICS)} 之一", 400)
    if not isinstance(history, list) or len(history) < 3:
        raise ApiError(40000, "history 至少需要 3 条", 400)
    if not isinstance(target, str) or not _MONTH_RE.match(target):
        raise ApiError(40000, f"target 格式应为 YYYY-MM：{target}", 400)

    seen = set()
    last_month = None
    for h in history:
        m, v = h.get("month"), h.get("value")
        if not isinstance(m, str) or not _MONTH_RE.match(m):
            raise ApiError(40000, f"history.month 格式应为 YYYY-MM：{m}", 400)
        if m in seen:
            raise ApiError(40000, f"history 存在重复月份：{m}", 400)
        seen.add(m)
        if isinstance(v, bool) or not isinstance(v, (int, float)) or v < 0:
            raise ApiError(40000, f"history[{m}].value 应为非负数值", 400)
        last_month = m if (last_month is None or m > last_month) else last_month
    if target <= last_month:
        raise ApiError(40000,
                       f"target({target}) 必须晚于最后一条 history.month({last_month})", 400)

    if occupancy_window:  # None 或空列表都视为无窗口
        for o in occupancy_window:
            date, rate = o.get("date"), o.get("rate")
            if not isinstance(date, str) or not _DATE_RE.match(date):
                raise ApiError(40000, f"occupancyWindow.date 格式应为 YYYY-MM-DD：{date}", 400)
            if isinstance(rate, bool) or not isinstance(rate, (int, float)) or not (0 <= rate <= 100):
                raise ApiError(40000, f"occupancyWindow.rate 应为 0-100 数值：{rate}", 400)


# --------------------------------------------------------------- 趋势基线 ---

def avg_mom_growth(values):
    """最近 3 段月环比均值（正负都保留）；第 0 段或除零跳过。"""
    rates = []
    for i in range(len(values) - 1, max(len(values) - 1 - MAX_MOM_SPAN, 0), -1):
        prev = values[i - 1]
        if prev != 0:
            rates.append((values[i] - prev) / abs(prev))
    return mean(rates) if rates else 0.0


def trend_point(values):
    """近 3 月加权平均 + 平均环比外推一个点（values 时间升序，近的在后，≥3 条）。

    TREND_WEIGHTS = (0.5, 0.3, 0.2) 是「近→远」，而 values[-3:] 是升序（最靠近的是最后一位），
    所以显式用下标让最新月拿 0.5、次新 0.3、最远 0.2。
    """
    v = values[-3:]
    wma = (v[-1] * TREND_WEIGHTS[0] + v[-2] * TREND_WEIGHTS[1] + v[-3] * TREND_WEIGHTS[2])
    return wma * (1 + avg_mom_growth(values))


# ----------------------------------------------------------------- 节假日 ---

def holiday_intensity(target_month, economic_holidays):
    """目标月内 economicHolidays 的天数 d（其余月份忽略）。"""
    if not economic_holidays:
        return 0
    return sum(1 for h in economic_holidays if isinstance(h, str) and h[:7] == target_month)


# ------------------------------------------------------------------- 天气 ---

def _fetch_wttr_in(city):
    """wttr.in j1 JSON：取近 3 日降水概率均值（0-100）做粗略气候特征。"""
    r = requests.get(f"https://wttr.in/{quote(city)}?format=j1",
                     timeout=WEATHER_TIMEOUT,
                     headers={"Accept": "application/json"})
    r.raise_for_status()
    j = r.json()
    weather = j.get("weather") or []
    probs = []
    for day in weather[:3]:
        hourly = day.get("hourly") or []
        if hourly:
            for hh in hourly[:8]:
                p = hh.get("chanceofrain")
                if isinstance(p, (int, float)) and not isinstance(p, bool):
                    probs.append(float(p))
    if not probs:
        return None
    return {"source": "wttr.in", "rain_prob": mean(probs)}


def _fetch_open_meteo(city):
    """open-meteo：geocode 城市 → 近 3 日降水概率最大期望均值。"""
    geo = requests.get("https://geocoding-api.open-meteo.com/v1/search",
                       params={"name": city, "count": 1}, timeout=WEATHER_TIMEOUT)
    geo.raise_for_status()
    results = (geo.json() or {}).get("results") or []
    if not results:
        return None
    lat, lon = results[0]["latitude"], results[0]["longitude"]
    fc = requests.get("https://api.open-meteo.com/v1/forecast",
                      params={"latitude": lat, "longitude": lon,
                              "daily": "precipitation_probability_max", "forecast_days": 3},
                      timeout=WEATHER_TIMEOUT)
    fc.raise_for_status()
    probs = (fc.json() or {}).get("daily", {}).get("precipitation_probability_max") or []
    probs = [p for p in probs if isinstance(p, (int, float)) and not isinstance(p, bool)]
    if not probs:
        return None
    return {"source": "open-meteo", "rain_prob": mean(probs)}


def fetch_climate(city):
    """按城市尝试免费天气接口，聚合简单气候特征；全部失败抛异常（调用方降级）。"""
    last_err = None
    for provider in (_fetch_wttr_in, _fetch_open_meteo):
        try:
            data = provider(city)
            if data:
                return data
        except Exception as e:  # noqa: BLE001 —— 天气是可选增强，任何失败都走降级
            last_err = e
            logger.debug("weather provider %s failed for %s: %s", provider.__name__, city, e)
    raise RuntimeError(f"天气接口不可用：{last_err}") if last_err else RuntimeError("天气接口无数据")


def apply_weather(point, climate):
    """天气对点位的轻微修正（±5% 封顶）；雨天概率高 → 需求轻度下调。"""
    if not climate:
        return point
    rain = climate.get("rain_prob", 50.0)
    shift = (50.0 - rain) / 100.0 * 0.10          # 0% 雨 → +5%；100% 雨 → -5%
    factor = 1.0 + max(-WEATHER_ADJ_CAP, min(WEATHER_ADJ_CAP, shift))
    return point * factor


# ---------------------------------------------------------------- 置信区间 ---

def backtest_mae(values):
    """近 6 月回测：对每个可回测月末（前有 ≥3 个历史月）用纯日历趋势预测，取绝对误差 MAE。

    返回 None 表示历史不足 6 月（调用方回退 ±8%）。
    """
    if len(values) < BACKTEST_MONTHS:
        return None
    errors = []
    start = max(len(values) - BACKTEST_MONTHS, 3)
    for i in range(start, len(values)):
        pred = trend_point(values[:i])           # 只用该月之前的数据，不回看未来
        errors.append(abs(values[i] - pred))
    return mean(errors) if errors else None


def confidence_interval(point, values, logs):
    """point ± radius；radius 用近 6 月回测 MAE×1.64（下限 point×5%），<6 月则 ±8%。"""
    mae = backtest_mae(values)
    if mae is not None:
        radius = max(mae * CONF_MAE_K, point * CONF_FLOOR_R)
        logs.append(f"置信区间：近{BACKTEST_MONTHS}月回测 MAE={mae:.2f}×{CONF_MAE_K}")
    else:
        radius = point * BACKTEST_FALLBACK_R
        logs.append(f"历史 <{BACKTEST_MONTHS} 月，置信区间使用 ±{BACKTEST_FALLBACK_R * 100:.0f}%")
    low = round(point - radius, 2)
    high = round(point + radius, 2)
    point = round(point, 2)
    # 恒 low < point < high（四舍五入兜底）
    if low >= point:
        low = round(point - 0.01, 2)
    if high <= point:
        high = round(point + 0.01, 2)
    return point, low, high


# ------------------------------------------------------------------- 主流程 ---

def predict(target, metric, history, occupancy_window=None,
            city=None, economic_holidays=None) -> dict:
    """SC-04 时序预测主入口。返回 §14.3 data 字段。"""
    history = sorted(history, key=lambda h: h["month"])
    occupancy_window = occupancy_window or []
    economic_holidays = economic_holidays or []
    validate_input(metric, history, target, occupancy_window)

    values = [float(h["value"]) for h in history]
    logs = []
    d = holiday_intensity(target, economic_holidays)

    if metric == "occupancy_rate":
        if occupancy_window:
            win_avg = mean(float(o["rate"]) for o in occupancy_window)
            hist_mean = mean(values)
            point = OCC_WINDOW_W * win_avg + OCC_HIST_W * hist_mean
            logs.append(
                f"入住率平滑：窗口{win_avg:.1f}×{OCC_WINDOW_W} + 历史均值{hist_mean:.1f}×{OCC_HIST_W}")
        else:
            point = trend_point(values)
        pp = min(d * HOLIDAY_OCC_PP_PER_DAY, HOLIDAY_OCC_PP_CAP)
        point += pp
        if pp:
            logs.append(f"节假日：{d} 天 → +{pp:.1f}pp")
    else:
        point = trend_point(values)
        factor = 1.0 + min(d * HOLIDAY_RATE_PER_DAY, HOLIDAY_RATE_CAP)
        if d:
            point *= factor
            logs.append(f"节假日：{d} 天 → ×{factor:.3f}")

    # 天气（可降级，不影响其它逻辑）
    weather_used = False
    if city:
        try:
            climate = fetch_climate(city)
            if climate:
                point = apply_weather(point, climate)
                weather_used = True
                logs.append(f"天气：{climate['source']} 降水概率≈{climate['rain_prob']:.0f}%，已轻微修正")
            else:
                logs.append("天气 degraded：接口无数据，继续纯日历+节假日")
        except Exception as e:  # noqa: BLE001 —— 天气失败不报错，只降级
            weather_used = False
            logs.append(f"天气 degraded：{e}")
    else:
        logs.append("city 为空，跳过天气（契约：可空降级）")
    degraded = not weather_used
    if metric == "occupancy_rate":
        point = min(100.0, max(0.0, point))

    point, low, high = confidence_interval(point, values, logs)

    return {
        "target": target,
        "metric": metric,
        "predictedValue": point,
        "confidenceLow": low,
        "confidenceHigh": high,
        "engine": ENGINE,
        "modelVersion": MODEL_VERSION,
        "method": "wma+holiday+weather(optional)",
        "weatherUsed": weather_used,
        "degraded": degraded,
        "logs": logs,
    }
