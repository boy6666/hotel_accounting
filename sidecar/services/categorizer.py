# -*- coding: utf-8 -*-
"""SC-03 智能归类建议。

一期：字符串相似度（精确/包含/difflib），不上模型；引擎名固定 "similarity-v1"，
二期可替换引擎并仍保留本函数签名（engine 字段随响应下发）。

口径（对齐 docs/08 §SC-03 与 docs/03 §12.5）：
  精确/包含匹配字典名 → 高置信（≥0.9）取 default_type；
  拼音/近义/切词相似 → 中置信提出候选（matched 仍为 True，带字典项）；
  完全不认识 → matched=false 低置信建议 variable。

无状态：本服务不保存学习规则；import_mapping_rule 由主后端在 confirm 时持久化。
"""
from difflib import SequenceMatcher

from core.utils import normalize_text

ENGINE = "similarity-v1"

DEFAULT_TYPE = "variable"     # 完全未知名时的兜底建议类型
UNKNOWN_CONFIDENCE = 0.30     # 完全未知名置信度
EXACT_CONFIDENCE = 1.00       # 精确命中
CONTAINS_CONFIDENCE = 0.93    # 子串包含
FUZZY_MATCH_THRESHOLD = 0.80  # difflib 高置信阈值
FUZZY_CANDIDATE_THRESHOLD = 0.55  # 中置信候选阈值


# 内置默认字典（= db/seed.sql 的 cost_item 示例，字段与 DB 对齐；主后端可传自己的字典覆盖）
DEFAULT_COST_ITEMS = [
    {"id": 1,  "name": "电费",     "defaultType": "fixed"},
    {"id": 2,  "name": "水费",     "defaultType": "fixed"},
    {"id": 3,  "name": "流量/宽带", "defaultType": "fixed"},
    {"id": 4,  "name": "投影会员",  "defaultType": "fixed"},
    {"id": 5,  "name": "日用品",    "defaultType": "variable"},
    {"id": 6,  "name": "布草洗涤",  "defaultType": "variable"},
    {"id": 7,  "name": "早餐",     "defaultType": "variable"},
    {"id": 8,  "name": "矿泉水",   "defaultType": "variable"},
    {"id": 9,  "name": "其它杂项",  "defaultType": "variable"},
    {"id": 10, "name": "接送/打车", "defaultType": "variable"},
    {"id": 11, "name": "水果饮料点心", "defaultType": "variable"},
    {"id": 12, "name": "刷单",     "defaultType": "one_time"},
    {"id": 13, "name": "暑假工",   "defaultType": "one_time"},
    {"id": 14, "name": "拍照费用", "defaultType": "one_time"},
    {"id": 15, "name": "门禁/房卡", "defaultType": "one_time"},
    {"id": 16, "name": "设施增加·维修", "defaultType": "one_time"},
    {"id": 17, "name": "花草绿植", "defaultType": "one_time"},
]


def _best_match(norm: str, items: list[dict]) -> tuple | None:
    """返回 (item, confidence, rule)；无匹配返回 None。"""
    best = None
    best_conf = 0.0
    for it in items:
        d = normalize_text(it.get("name"))
        if not d:
            continue
        conf = 0.0
        rule = ""
        if norm == d:
            conf, rule = EXACT_CONFIDENCE, "exact"
        elif norm and (norm in d or d in norm):
            # 子串包含：至少让较短那方有 ≥1 个有效字符（苗/长名匹配都成立）
            short = min(norm, d, key=len)
            if len(short) >= 1:
                conf, rule = CONTAINS_CONFIDENCE, "contains"
        else:
            r = SequenceMatcher(None, norm, d).ratio()
            if r >= FUZZY_MATCH_THRESHOLD:
                conf, rule = round(r, 3), "fuzzy"
            elif r >= FUZZY_CANDIDATE_THRESHOLD:
                conf, rule = round(r, 3), "fuzzy_candidate"
        if conf > best_conf:
            best_conf = conf
            best = (it, conf, rule)
    if best and best_conf >= FUZZY_CANDIDATE_THRESHOLD:
        return best
    return None


def categorize_names(raw_names: list[str], cost_items: list[dict] | None = None) -> dict:
    """输入原始名称数组 + 可选字典 → {engine, confirmed, needReview, items}"""
    items = cost_items if cost_items is not None else DEFAULT_COST_ITEMS
    # 归一化字典名一次（提升含空格/标点字典的匹配）
    normalized_items = [
        {"id": it.get("id"), "name": it.get("name") or "", "defaultType": it.get("defaultType") or DEFAULT_TYPE,
         "_norm": normalize_text(it.get("name"))}
        for it in items
    ]

    out = []
    for raw in raw_names:
        norm = normalize_text(raw)
        match = _best_match(norm, normalized_items) if norm else None
        if match is None:
            out.append({
                "rawName": raw,
                "suggestCostItemId": None,
                "suggestType": DEFAULT_TYPE,
                "confidence": UNKNOWN_CONFIDENCE,
                "matched": False,
                "rule": "unknown",
            })
        else:
            it, conf, rule = match
            out.append({
                "rawName": raw,
                "suggestCostItemId": it.get("id"),
                "suggestType": it.get("defaultType") or DEFAULT_TYPE,
                "confidence": conf,
                "matched": True,
                "rule": rule,
            })

    confirmed = sum(1 for x in out if x["matched"] and x["confidence"] >= 0.9)
    return {
        "engine": ENGINE,
        "confirmed": confirmed,
        "needReview": len(out) - confirmed,
        "items": out,
    }
