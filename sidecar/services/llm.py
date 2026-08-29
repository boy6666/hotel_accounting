# -*- coding: utf-8 -*-
"""SC-05 DeepSeek LLM 解读（红线：只送聚合摘要，不送个人/敏感字段）。

对齐 docs/08 §SC-05 与 docs/03 §14.4：
  - API key 从环境变量读 DEEPSEEK_API_KEY（可选 DEEPSEEK_BASE_URL / DEEPSEEK_MODEL 覆盖），不落库；
  - 三态（都 code=0，主后端不同分支）：
      * 未配置 key → {llmAvailable:false, interpretation:null}（不发起网络，毫秒级）；
      * 调用成功 → {llmAvailable:true, interpretation};
      * 上游超时/4xx/5xx/JSON 异常 → 降级 {llmAvailable:false, interpretation:null}（记 warning 不抛错）。
  - 网络层抽象为模块级 _post，测试可 patch requests.post。

红线执行：出网 body 只由白名单聚合字段（metric/predictedValue/historyHeadline/ask）拼装，
绝不回显整包请求；即使调用方带了 idCard/name 等额外字段（pydantic extra=ignore 已丢弃），
也不会进入发送内容。
"""
import logging
import os

import requests

logger = logging.getLogger("sidecar.llm")

DEEPSEEK_DEFAULT_BASE_URL = "https://api.deepseek.com"
DEEPSEEK_DEFAULT_MODEL = "deepseek-chat"
REQUEST_TIMEOUT = 15.0  # 秒

# 输出只允许这些请求字段进入发送内容（聚合数字/费用名摘要）
_WHITELIST = ("metric", "predictedValue", "historyHeadline", "ask")

SYSTEM_PROMPT = (
    "你是酒店经营分析助手。只基于所给数字与费用名摘要作答，不得编造数据。"
    "不索要、不输出任何个人、政治、安全敏感内容。回答不超过 200 字。"
)
DEFAULT_ASK = "解读这个指标的趋势并给出定价/经营建议。"


def env_config():
    """DeepSeek 配置：全部从环境变量读，不落库。"""
    return {
        "api_key": (os.environ.get("DEEPSEEK_API_KEY") or "").strip() or None,
        "base_url": (os.environ.get("DEEPSEEK_BASE_URL") or DEEPSEEK_DEFAULT_BASE_URL).rstrip("/"),
        "model": os.environ.get("DEEPSEEK_MODEL") or DEEPSEEK_DEFAULT_MODEL,
    }


def build_messages(payload):
    """只拼白名单聚合字段（红线）；其余一概不进上下文。"""
    text = (f"指标：{payload.get('metric', '')}\n"
            f"预测值：{payload.get('predictedValue')}\n"
            f"历史摘要：{payload.get('historyHeadline', '')}\n"
            f"请：{(payload.get('ask') or DEFAULT_ASK).strip()}")
    return [
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "user", "content": text},
    ]


def build_request_body(payload, cfg):
    return {
        "model": cfg["model"],
        "messages": build_messages(payload),
        "max_tokens": 300,
        "temperature": 0.4,
    }


def interpret(payload) -> dict:
    """三态解读入口，返回 {llmAvailable, interpretation}；任何失败都降级不抛错。"""
    if payload is None:
        payload = {}
    cfg = env_config()
    if not cfg["api_key"]:
        logger.info("未配置 DEEPSEEK_API_KEY，跳过 LLM（纯统计兜底）")
        return {"llmAvailable": False, "interpretation": None}

    body = build_request_body(payload, cfg)
    headers = {"Authorization": f"Bearer {cfg['api_key']}", "Content-Type": "application/json"}
    url = f"{cfg['base_url']}/chat/completions"
    try:
        resp = requests.post(url, json=body, headers=headers, timeout=REQUEST_TIMEOUT)
        resp.raise_for_status()          # 4xx/5xx 抛错 → 走降级
        data = resp.json()               # JSON 异常 → 走降级
        content = data["choices"][0]["message"]["content"]
        if not isinstance(content, str) or not content.strip():
            raise ValueError("LLM 返回空内容")
        return {"llmAvailable": True, "interpretation": content.strip()}
    except Exception as e:  # noqa: BLE001 —— 上游超时/状态码/结构异常统一降级
        logger.warning("DeepSeek 调用失败，降级纯统计：%s", e)
        return {"llmAvailable": False, "interpretation": None}
