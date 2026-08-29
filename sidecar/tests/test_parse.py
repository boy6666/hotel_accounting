# -*- coding: utf-8 -*-
"""SC-02 解析测试：与 db/seed.sql 三步对齐 + 坏文件 50300 带行号 + 路客云直用。"""
import json
import os

import pytest

from core.errors import ApiError, PARSE_ERROR
from services import excel_parser
from tests.make_fixtures import build_broken_file


def test_parse_count_tmpl_aligned_with_seed(fixtures):
    """旧模板（房型×日计数）解析：成本 10 行 34500 / 房态 22 日 198 / 销售 6 渠道 198。"""
    data = excel_parser.parse_workbook(fixtures["count"], "2026-08")

    # ---- 费用流水 ----
    assert len(data["costs"]) == 10
    assert abs(data["costTotal"] - 34500.0) < 0.001
    first = data["costs"][0]
    assert first["rawName"] == "电费"
    assert first["amount"] == 8500.0
    assert first["rowNo"] == 5
    assert first["type"] == "fixed"          # 表里 D 列写了「固定」
    assert first["note"] is None
    # 暑假工行：类型一次性 + 备注
    sw = next(c for c in data["costs"] if c["rawName"] == "暑假工")
    assert sw["type"] == "one_time"
    assert sw["note"] == "7-8月临时工"
    # 每行都带齐关键字段
    for c in data["costs"]:
        assert set(("rowNo", "rawName", "amount", "type", "note")) <= set(c)

    # ---- 每日房态 ----
    assert data["occupancyLayout"] == "room_type_count"
    assert len(data["occupancy"]) == 22
    assert data["occupancyTotal"] == 198
    assert all(r["roomNos"] == [] for r in data["occupancy"])   # 计数布局无具体房号
    assert sum(r["occupiedCount"] for r in data["occupancy"]) == 198
    assert data["occupancy"][0]["bizDate"] == "2026-08-01"
    assert data["occupancy"][-1]["bizDate"] == "2026-08-22"

    # ---- 渠道销售 ----
    assert len(data["channels"]) == 6
    assert data["channelNights"] == 198
    assert abs(data["channelRevenue"] - 61960.0) < 0.001
    names = [ch["rawName"] for ch in data["channels"]]
    assert names == ["携程", "美团", "飞猪/去哪儿", "抖音/其它", "前台散客", "协议/中介"]
    # 缺失佣金由主后端补算 → 此处置空
    assert "commission" not in data["channels"][0]

    # ---- 摘要 ----
    assert data["rawNameSummary"].startswith("8月：")


def test_parse_sparse_days_compat(fixtures):
    """稀疏天数列布局：行=房号、列=1..31 → 多天同房号集合，仍与 seed 对齐。"""
    data = excel_parser.parse_workbook(fixtures["sparse"], "2026-08")
    assert data["occupancyLayout"] == "sparse_days"
    assert len(data["occupancy"]) == 22
    assert data["occupancyTotal"] == 198
    assert data["occupancy"][0]["bizDate"] == "2026-08-01"
    # 8/1 满房 10 间（seed 首日 10）
    assert sorted(data["occupancy"][0]["roomNos"]) == [
        "101", "102", "103", "104", "105", "201", "202", "203", "204", "205"]
    # 8/22 只住 2 间
    last = data["occupancy"][-1]
    assert last["bizDate"] == "2026-08-22"
    assert len(last["roomNos"]) == 2
    # 成本/渠道仍解析
    assert len(data["costs"]) == 10
    assert len(data["channels"]) == 6


def test_parse_bad_amount_raises_with_row(fixtures):
    """坏金额 → code=50300 且带行号。"""
    with pytest.raises(ApiError) as ei:
        excel_parser.parse_workbook(fixtures["bad_amount"], "2026-08")
    assert ei.value.code == PARSE_ERROR
    assert "第 6 行" in ei.value.message
    assert "水费" in ei.value.message


def test_parse_missing_file_raises():
    with pytest.raises(ApiError) as ei:
        excel_parser.parse_workbook(os.path.join("C:", os.sep, "nope", "x.xlsx"), "2026-08")
    assert ei.value.code == PARSE_ERROR
    assert "文件不存在" in ei.value.message


def test_parse_lukeyun_channel_derived_occupancy(fixtures):
    """路客云订单导出直用：渠道按『计入统计=是』聚合并、按入住月归属；
    房态由『已排房=是 + 房间号』自动推导整月矩阵；隐私列绝不进入结果。"""
    data = excel_parser.parse_workbook(fixtures["lukeyun"], "2026-08")

    # ---- 渠道聚合（未匹配渠道名『自来客』由后端按线下处理，佣金 0）----
    assert data["channelNights"] == 8
    assert abs(data["channelRevenue"] - 4299.0) < 0.001
    agg = {ch["rawName"]: ch for ch in data["channels"]}
    assert list(agg) == ["携程", "美团", "自来客"]
    assert agg["携程"]["nights"] == 3 and agg["携程"]["revenue"] == 1500.0
    assert agg["携程"]["commission"] == 150.0
    assert agg["美团"]["nights"] == 3 and agg["美团"]["revenue"] == 1500.0
    assert agg["美团"]["commission"] == 150.0
    assert agg["自来客"]["nights"] == 2 and agg["自来客"]["revenue"] == 1299.0
    assert agg["自来客"]["commission"] == 0.0
    # o6：计入统计=否 → 不进渠道；o7：7月入住 → 归其入住月，本月不算

    # ---- 房态矩阵：整月 31 天，只含 已排房=是 + 具体房间 ----
    assert data["occupancyLayout"] == "lukeyun_derived"
    assert len(data["occupancy"]) == 31
    assert data["occupancy"][0]["bizDate"] == "2026-08-01"
    assert data["occupancy"][-1]["bizDate"] == "2026-08-31"
    by_day = {r["bizDate"]: r["roomNos"] for r in data["occupancy"]}
    assert by_day["2026-08-01"] == ["201"]
    assert by_day["2026-08-02"] == ["201"]
    assert by_day["2026-08-03"] == ["103"]
    assert by_day["2026-08-05"] == ["101"]
    assert by_day["2026-08-07"] == ["203"]
    assert by_day["2026-08-08"] == ["203"]
    assert by_day["2026-08-04"] == []      # 美团 o6 不计入 → 空（o3 在 8/3）
    assert by_day["2026-08-20"] == []      # 未排房 o4 → 不计入房态
    assert data["occupancyTotal"] == 6     # 8 渠道夜 − 2 夜未排房 → 对账差 2

    # ---- 隐私红线（SC-05）：预订人/手机号绝不进入任何结果 ----
    blob = json.dumps(data, ensure_ascii=False)
    assert "测试客" not in blob
    assert "13800000000" not in blob
    assert "预订人" not in blob
    assert "手机号" not in blob


def test_parse_lukeyun_without_occupancy_sheet(fixtures):
    """模板已无『每日房态』表：路客云模式下缺该表不报错，房态直接由订单推导。"""
    data = excel_parser.parse_workbook(fixtures["lukeyun"], "2026-08")
    assert data["occupancyLayout"] == "lukeyun_derived"
    assert len(data["occupancy"]) == 31
    assert data["occupancyTotal"] == 6            # 与 _build_lukeyun_channel_sheet 预期一致
    assert data["channelNights"] == 8


def test_parse_manual_without_occupancy_sheet_raises(fixtures):
    """手工(非路客云)布局缺『每日房态』表 → 50300，消息引导用路客云订单式。"""
    with pytest.raises(ApiError) as ei:
        excel_parser.parse_workbook(fixtures["no_occupancy_manual"], "2026-08")
    assert ei.value.code == PARSE_ERROR
    assert "每日房态" in ei.value.message
    assert "路客云" in ei.value.message


def test_parse_broken_xlsx_raises():
    path = build_broken_file()
    with pytest.raises(ApiError) as ei:
        excel_parser.parse_workbook(path, "2026-08")
    assert ei.value.code == PARSE_ERROR
