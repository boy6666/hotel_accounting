# -*- coding: utf-8 -*-
"""通用工具：Sheet 名兼容、单元格归一化、金额/日期解析（SC-01）"""
import re
import unicodedata

# ---------------------------------------------------------------- 文本归一化 ---

# 归一化时忽略的常见标点（中英文均去，便于对齐不同写法的字典名）
_PUNCT_RE = re.compile(r"[\s·、/\\\-_()（）【】\[\]{}「」『』:：,，.．~～!！?？*＊+＋|]+")


def normalize_text(s: object) -> str:
    """去空白/标点，统一全角→半角（逻辑上用于比较的键）。"""
    if s is None:
        return ""
    if isinstance(s, bool):
        return ""
    text = unicodedata.normalize("NFKC", str(s))
    return _PUNCT_RE.sub("", text)


def clean_cell(v: object) -> object:
    """原样返回单元格值（去掉纯空白串）。"""
    if v is None:
        return None
    if isinstance(v, str) and not v.strip():
        return None
    return v


def is_blank(v: object) -> bool:
    if v is None:
        return True
    if isinstance(v, str) and not v.strip():
        return True
    return False


# ----------------------------------------------------------------- 金额/数值 ---

_AMOUNT_RE = re.compile(r"-?[0-9]+(?:\.[0-9]+)?")
_THOUSAND_COMMA_RE = re.compile(r"[,，]")


def parse_amount(v: object) -> float | None:
    """把单元格转成金额（浮点，两位小数）。支持 "1,234.5" / 全角逗号 等。"""
    if is_blank(v):
        return None
    if isinstance(v, bool):
        return None
    if isinstance(v, (int, float)):
        return round(float(v), 2)
    s = str(v).strip()
    if not s or s.lower().startswith("="):  # 公式不在这里算（data_only 才可能拿到缓存值）
        return None
    s = _THOUSAND_COMMA_RE.sub("", s)
    m = _AMOUNT_RE.search(s)
    if not m:
        return None
    return round(float(m.group(0)), 2)


# ------------------------------------------------------------------ 日期/日 ---


def parse_day(v: object) -> int | None:
    """把『日期』单元格解析成 1..31 的日号。容忍 int / '5' / '8月5日' / '2026-08-05' / '8-5'。"""
    if is_blank(v) or isinstance(v, bool):
        return None
    if isinstance(v, (int, float)):
        d = int(v)
        return d if 1 <= d <= 31 else None
    s = str(v).strip()
    if s.isdigit():
        d = int(s)
        return d if 1 <= d <= 31 else None
    digits = re.findall(r"\d{1,4}", s)
    if not digits:
        return None
    # 取最后一段数字（日期形式的‘日；形如 2026-8-5 → 5；8-5 → 5；8月5日 → 5）
    last = digits[-1]
    d = int(last)
    if 1 <= d <= 31:
        return d
    # 最后一段不是日（如结尾是年份/月份怪格式），退回找 1..31
    for tok in reversed(digits):
        dd = int(tok)
        if 1 <= dd <= 31:
            return dd
    return None


def biz_date(month: str, day: int) -> str:
    """YYYY-MM + 日 → YYYY-MM-DD（month 需已是 YYYY-MM）"""
    return f"{month}-{day:02d}"


# ------------------------------------------------------------------- Sheet 匹配 ---


def sheet_rank(sheet_title: str) -> tuple:
    """按关键词给 Sheet 名义打分，返回 (成本分, 房态分, 销售分)。

    分最高的那个 Sheet 承担对应表；保证『中文名可含空格/别名容忍』：
      成本：当月成本 / 费用流水 / 成本明细
      房态：每日房态 / 日房态 / 入住 / 房态
      销售：当月销售利润 / 销售 / 渠道 / 利润
    """
    t = normalize_text(sheet_title or "")
    if not t:
        return (0, 0, 0)
    score_cost = 0
    score_occ = 0
    score_sale = 0
    # 成本
    for kw in ("当月成本", "成本", "费用流水", "费用明细", "费用"):
        if kw in t:
            score_cost += 3 if kw.startswith("当月") else 1
    # 房态
    if "每日房态" in t:
        score_occ += 5
    for kw in ("房态", "日房态", "入住", "房间"):
        if kw in t:
            score_occ += 1
    # 销售
    for kw in ("当月销售利润", "月销售利润", "销售利润", "销售收入", "销售", "渠道", "利润"):
        if kw in t:
            score_sale += 3 if kw.startswith("当月") else 1
    # 路客云订单式销售表优先（与『当月销售利润』同名或单独命名都认；两份都有时用订单式）
    if "路客云" in t:
        score_sale += 8
    return (score_cost, score_occ, score_sale)


def pick_sheets(wb):
    """按关键词在三个类型里各挑 1 个 Sheet；缺的 raise（由调用方转 50300）。"""
    best = {"cost": None, "occupancy": None, "channel": None}
    best_score = {"cost": 0, "occupancy": 0, "channel": 0}
    for ws in wb.worksheets:
        c, o, s = sheet_rank(ws.title)
        if c > best_score["cost"]:
            best["cost"], best_score["cost"] = ws, c
        if o > best_score["occupancy"]:
            best["occupancy"], best_score["occupancy"] = ws, o
        if s > best_score["channel"]:
            best["channel"], best_score["channel"] = ws, s
    return best
