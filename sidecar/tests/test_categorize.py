# -*- coding: utf-8 -*-
"""SC-03 归类测试：seed 的 10 个费用原名全高置信匹配；one_time 词给类型；未知名兜底。"""
from services import categorizer
from services.categorizer import DEFAULT_COST_ITEMS

SEED_NAMES = ["电费", "水费", "流量/宽带", "日用品", "布草洗涤", "早餐",
              "矿泉水", "其它杂项", "水果饮料点心", "暑假工"]


def test_seed_10_names_high_confidence():
    data = categorizer.categorize_names(SEED_NAMES)
    assert data["engine"] == "similarity-v1"
    assert len(data["items"]) == 10
    high = [it for it in data["items"] if it["matched"] and it["confidence"] >= 0.9]
    assert len(high) >= 8            # 验收：≥8 高置信
    assert data["confirmed"] == 10


def test_one_time_keywords_recommend_type():
    for kw in ("刷单", "暑假工", "拍照费用", "门禁/房卡"):
        it = categorizer.categorize_names([kw])["items"][0]
        assert it["matched"] is True
        assert it["suggestType"] == "one_time"


def test_unknown_falls_back_to_variable():
    it = categorizer.categorize_names(["奶茶"])["items"][0]
    assert it["matched"] is False
    assert it["suggestCostItemId"] is None
    assert it["suggestType"] == "variable"
    assert it["confidence"] < 0.9


def test_fuzzy_and_punct_alias():
    # 模板写法『设施增加*维修』 ~ 字典原名『设施增加·维修』
    it = categorizer.categorize_names(["设施增加*维修"])["items"][0]
    assert it["matched"] is True
    assert it["suggestCostItemId"] == 16
    assert it["suggestType"] == "one_time"
    # 近义词/变体：多字仍能命中字典
    it2 = categorizer.categorize_names(["布草洗涤费"])["items"][0]
    assert it2["matched"] is True


def test_custom_dict_override():
    custom = [{"id": 99, "name": "电费", "defaultType": "fixed"}]
    it = categorizer.categorize_names(["电费（店铺）"], custom)["items"][0]
    assert it["suggestCostItemId"] == 99
    assert it["suggestType"] == "fixed"
