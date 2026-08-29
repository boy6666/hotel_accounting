# -*- coding: utf-8 -*-
"""API 层测试：信封、health、parse/categorize 走 HTTP、错误码信封。"""
from fastapi.testclient import TestClient

from app import app

client = TestClient(app)


def test_health_envelope():
    resp = client.get("/api/health")
    assert resp.status_code == 200
    body = resp.json()
    assert body["code"] == 0
    assert body["message"] == "ok"
    assert body["data"]["status"] == "up"
    assert body["data"]["version"] == "1.1.0"


def test_categorize_http(fixtures):
    resp = client.post("/api/categorize", json={
        "rawNames": ["电费", "水费", "奶茶"],
    })
    assert resp.status_code == 200
    body = resp.json()
    assert body["code"] == 0
    data = body["data"]
    assert data["engine"] == "similarity-v1"
    assert data["confirmed"] >= 2
    assert data["items"][0]["rawName"] == "电费"
    # 自定义字典
    resp2 = client.post("/api/categorize", json={
        "rawNames": ["电费"],
        "costItems": [{"id": 1, "name": "电费", "defaultType": "fixed"}],
    })
    assert resp2.json()["data"]["items"][0]["suggestCostItemId"] == 1


def test_parse_http(fixtures):
    resp = client.post("/api/parse", json={
        "file_path": fixtures["count"],
        "month": "2026-08",
    })
    assert resp.status_code == 200
    body = resp.json()
    assert body["code"] == 0
    data = body["data"]
    assert data["costTotal"] == 34500.0
    assert data["occupancyTotal"] == 198
    assert data["channelNights"] == 198


def test_parse_bad_month_http():
    resp = client.post("/api/parse", json={"file_path": "x.xlsx", "month": "2026-13"})
    assert resp.status_code == 400
    body = resp.json()
    assert body["code"] == 40000


def test_parse_missing_file_http():
    resp = client.post("/api/parse", json={"file_path": "C:/none/x.xlsx", "month": "2026-08"})
    assert resp.status_code == 422
    body = resp.json()
    assert body["code"] == 50300
    assert "文件不存在" in body["message"]


def test_categorize_invalid_payload():
    resp = client.post("/api/categorize", json={"rawNames": []})
    assert resp.status_code == 422          # pydantic 校验失败
