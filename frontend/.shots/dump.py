# -*- coding: utf-8 -*-
# 独立验收：文本化 dump 各页 DOM 骨架（不存图）。用法：python .shots/dump.py [--mock/--real]
import sys, os, json
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
from playwright.sync_api import sync_playwright

BASE = "http://127.0.0.1:5174/"

PAGES = [
    ("dashboard", "首页看板"),
    ("cost", "成本分析"),
    ("channels", "销售渠道"),
    ("profit", "利润分析"),
    ("occupancy", "房态·入住率"),
    ("pricing", "定价·预测"),
    ("breakeven", "回本测算"),
    ("settings", "设置·基础数据"),
]

def summarize_text(txt):
    lines = [l.strip() for l in txt.splitlines() if l.strip()]
    return " | ".join(lines)

with sync_playwright() as p:
    b = p.chromium.launch()
    pg = b.new_page(viewport={"width": 1440, "height": 1000})
    errors = []
    pg.on("pageerror", lambda e: errors.append("PAGEERROR: " + str(e)[:300]))
    pg.on("console", lambda m: errors.append(f"CONSOLE[{m.type}]: {m.text[:300]}") if m.type in ("error", "warning") else None)
    pg.goto(BASE, wait_until="networkidle", timeout=30000)
    pg.wait_for_timeout(400)
    # 登录
    try:
        pg.click("button[type=submit]", timeout=8000)
        pg.wait_for_timeout(2200)
    except Exception as e:
        print("login issue:", str(e)[:120])
    pg.wait_for_selector("nav a", timeout=10000)
    pg.wait_for_timeout(800)
    print("==== LOGIN PAGE / post-login state ====")
    print("url:", pg.url)
    print("has sidebar:", pg.query_selector("aside.sidebar") is not None)
    print("active nav:", pg.eval_on_selector("nav a.active", "el => el.textContent.trim()") if pg.query_selector("nav a.active") else "none")
    print("h1:", pg.eval_on_selector(".topbar h1", "el => el.textContent.trim()") if pg.query_selector(".topbar h1") else "none")
    print("sub:", pg.eval_on_selector(".topbar .sub", "el => el.textContent.trim()") if pg.query_selector(".topbar .sub") else "none")
    print("top-actions btns:", pg.eval_on_selector_all(".top-actions button, .top-actions input", "els => els.map(e => (e.tagName==='INPUT'?('month:'+e.value):e.textContent.trim())).join(' | ')"))
    print("----")

    for slug, label in PAGES:
        try:
            pg.click(f"nav a:has-text('{label}')", timeout=6000)
            pg.wait_for_timeout(2200)
        except Exception as e:
            print(f"[{slug}] nav fail:", str(e)[:120])
            continue
        body = pg.query_selector("div.page")
        txt = body.inner_text() if body else "(no .page)"
        print(f"==== {slug} — {label} ====")
        print("active nav:", pg.eval_on_selector("nav a.active", "el => el.textContent.trim()") if pg.query_selector("nav a.active") else "none")
        # 结构计数
        stats = {}
        for sel in [".card.stat", ".notice", ".ai-box", ".calc", ".tiers", "table.data-table", "table.rmg", ".empty", ".tag", "canvas", "select", "input[type=range]"]:
            stats[sel] = pg.eval_on_selector_all(sel, "els => els.length")
        print("struct:", json.dumps(stats, ensure_ascii=False))
        # 再看有没有 pageerror / loading 残留
        print("has .page-loading:", pg.query_selector(".page-loading") is not None)
        print("TEXT:", summarize_text(txt))
        print("----")

    print("==== CAPTURED JS CONSOLE/PAGE ERRORS ====")
    if errors:
        seen = set()
        for e in errors:
            key = e[:80]
            if key in seen: continue
            seen.add(key)
            print(e)
    else:
        print("(none)")
    b.close()
print("DONE")
