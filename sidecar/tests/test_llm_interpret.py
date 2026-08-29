# -*- coding: utf-8 -*-
"""SC-05 /api/llm/interpret 测试：三态（无 key/成功/降级）+ 红线（只送聚合摘要）。

网络层 mock：patch services.llm.requests.post（monkeypatch 自动还原）。
"""
import requests
from fastapi.testclient import TestClient

import services.llm as llm
from app import app

client = TestClient(app)


class FakeResp:
    """模拟 requests.Response 的最小面。"""

    def __init__(self, status=200, payload=None):
        self.status_code = status
        self._payload = payload

    def raise_for_status(self):
        if self.status_code >= 400:
            raise requests.HTTPError(f"HTTP {self.status_code}")

    def json(self):
        return self._payload


class PostRecorder:
    """记录每次 requests.post 的入参，并按预设返回/抛错。"""

    def __init__(self, result=None, exc=None):
        self.calls = []
        self._result = result
        self._exc = exc

    def __call__(self, url, **kwargs):
        self.calls.append({"url": url, **kwargs})
        if self._exc is not None:
            raise self._exc
        return self._result


def _post_json(**overrides):
    payload = {
        "metric": "revenue",
        "predictedValue": 63000.0,
        "historyHeadline": "近8月收入 41200→61960，环比 +8%",
        "ask": "解读趋势并给出定价/经营建议，200字内",
    }
    payload.update(overrides)
    resp = client.post("/api/llm/interpret", json=payload)
    assert resp.status_code == 200, resp.text
    return resp.json()["data"]


# ------------------------------------------------------------------ 三态 ---

def test_interpret_no_key_no_network(monkeypatch):
    """无 DEEPSEEK_API_KEY → llmAvailable:false 且未发起网络。"""
    monkeypatch.delenv("DEEPSEEK_API_KEY", raising=False)
    rec = PostRecorder()
    monkeypatch.setattr("services.llm.requests.post", rec)
    data = _post_json()
    assert data == {"llmAvailable": False, "interpretation": None}
    assert rec.calls == []


def test_interpret_success(monkeypatch):
    """调用成功 → interpretation 透传。"""
    monkeypatch.setenv("DEEPSEEK_API_KEY", "sk-test")
    rec = PostRecorder(result=FakeResp(status=200, payload={
        "choices": [{"message": {"content": "收入上行，建议保持当前挂牌价并关注 9 月中秋档。"}}],
    }))
    monkeypatch.setattr("services.llm.requests.post", rec)
    data = _post_json()
    assert data["llmAvailable"] is True
    assert "上" in data["interpretation"]
    assert len(rec.calls) == 1
    assert rec.calls[0]["url"] == "https://api.deepseek.com/chat/completions"
    # 出网 body 只含白名单键
    sent = rec.calls[0]["json"]
    assert set(sent.keys()) <= {"model", "messages", "max_tokens", "temperature"}


def test_interpret_custom_base_url_model(monkeypatch):
    """DEEPSEEK_BASE_URL / DEEPSEEK_MODEL 可覆盖。"""
    monkeypatch.setenv("DEEPSEEK_API_KEY", "sk-test")
    monkeypatch.setenv("DEEPSEEK_BASE_URL", "http://127.0.0.1:9999/v1")
    monkeypatch.setenv("DEEPSEEK_MODEL", "custom-model")
    rec = PostRecorder(result=FakeResp(status=200, payload={
        "choices": [{"message": {"content": "ok"}}]}))
    monkeypatch.setattr("services.llm.requests.post", rec)
    data = _post_json()
    assert data["llmAvailable"] is True
    call = rec.calls[0]
    assert call["url"] == "http://127.0.0.1:9999/v1/chat/completions"
    assert call["json"]["model"] == "custom-model"


def test_interpret_upstream_5xx_degrades(monkeypatch):
    monkeypatch.setenv("DEEPSEEK_API_KEY", "sk-test")
    rec = PostRecorder(result=FakeResp(status=500))
    monkeypatch.setattr("services.llm.requests.post", rec)
    data = _post_json()
    assert data == {"llmAvailable": False, "interpretation": None}


def test_interpret_timeout_degrades(monkeypatch):
    monkeypatch.setenv("DEEPSEEK_API_KEY", "sk-test")
    rec = PostRecorder(exc=requests.Timeout("connect timeout"))
    monkeypatch.setattr("services.llm.requests.post", rec)
    data = _post_json()
    assert data == {"llmAvailable": False, "interpretation": None}


class BadJsonResp(FakeResp):
    """200 但 body 不是合法 JSON。"""

    def json(self):
        raise ValueError("Expecting value: line 1 column 1")


def test_interpret_bad_json_degrades(monkeypatch):
    monkeypatch.setenv("DEEPSEEK_API_KEY", "sk-test")
    rec = PostRecorder(result=BadJsonResp(status=200))
    monkeypatch.setattr("services.llm.requests.post", rec)
    data = _post_json()
    assert data == {"llmAvailable": False, "interpretation": None}


# ------------------------------------------------------------------ 红线 ---

def test_interpret_redline_no_idcard_name(monkeypatch):
    """请求里带假 idCard/name/phone → 确认不会外传（出网 body 不含这些字段/值）。"""
    monkeypatch.setenv("DEEPSEEK_API_KEY", "sk-test")
    rec = PostRecorder(result=FakeResp(status=200, payload={
        "choices": [{"message": {"content": "ok"}}]}))
    monkeypatch.setattr("services.llm.requests.post", rec)
    data = _post_json(
        idCard="330106199001011234",      # 假身份证
        name="张三",                       # 假姓名
        phone="13800000000",
        address="杭州市某某路1号",
    )
    assert data["llmAvailable"] is True
    sent_str = str(rec.calls[0]["json"])
    for bad in ("idCard", "330106199001011234", "name", "张三",
                "phone", "13800000000", "地址", "某某路"):
        assert bad not in sent_str
    # 白名单聚合字段仍在
    assert "revenue" in sent_str
    assert "63000.0" in sent_str
    assert "近8月收入" in sent_str


def test_build_messages_whitelist_only():
    payload = {"metric": "revenue", "predictedValue": 63000.0,
               "historyHeadline": "近8月收入 41200→61960", "ask": "解读",
               "idCard": "X", "name": "Y"}
    joined = "\n".join(m["content"] for m in llm.build_messages(payload))
    assert "X" not in joined and "Y" not in joined
    assert "revenue" in joined and "63000.0" in joined
