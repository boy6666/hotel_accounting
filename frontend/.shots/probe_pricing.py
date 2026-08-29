# -*- coding: utf-8 -*-
# 聚焦 pricing / breakeven 交互验收：抓 pageerror、点按钮、看结构变化
import sys, json
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
from playwright.sync_api import sync_playwright

BASE = "http://127.0.0.1:5174/"
errors = []

def dump(pg, tag):
    body = pg.query_selector("div.page")
    txt = (body.inner_text() if body else "(no .page)").replace("\n", " | ")
    print(f"[{tag}]", txt[:900])

with sync_playwright() as p:
    b = p.chromium.launch()
    pg = b.new_page(viewport={"width": 1440, "height": 1000})
    pg.on("pageerror", lambda e: errors.append("PAGEERROR: " + str(e)[:200]))
    pg.goto(BASE, wait_until="networkidle", timeout=30000)
    pg.wait_for_timeout(400)
    pg.click("button[type=submit]", timeout=8000)
    pg.wait_for_timeout(2000)
    pg.wait_for_selector("nav a", timeout=10000)

    # ---- pricing ----
    pg.click("nav a:has-text('定价·预测')", timeout=6000)
    pg.wait_for_timeout(1500)
    print("== pricing initial ==")
    dump(pg, "init")
    # 点击生成建议价
    try:
        pg.click("button:has-text('生成建议价')", timeout=6000)
        pg.wait_for_timeout(3500)
        print("== after generate suggestions ==")
        dump(pg, "gen")
        stats = pg.eval_on_selector_all(".card.stat", "els => els.map(e=>e.innerText.replace(/\\n/g,' '))")
        print("stat cards:", json.dumps(stats, ensure_ascii=False))
        cans = pg.eval_on_selector_all("canvas", "els => els.length")
        print("canvas count:", cans)
        tbl = pg.query_selector("table.data-table")
        print("sug table rows:", pg.eval_on_selector_all("table.data-table tbody tr", "els => els.length") if tbl else 0)
    except Exception as e:
        print("generate fail:", str(e)[:200])
    # 目标倒推 now
    out = pg.eval_on_selector(".calc .out", "el => el && el.textContent.trim()") if pg.query_selector(".calc .out") else "none"
    print("target calc out:", out)
    # 生成预测
    try:
        pg.click("button:has-text('生成预测')", timeout=6000)
        pg.wait_for_timeout(3500)
        dump(pg, "pred")
        ai = pg.query_selector(".ai-box")
        print("ai-box:", (ai.inner_text()[:300].replace("\n", " | ") if ai else "none"))
    except Exception as e:
        print("predict fail:", str(e)[:200])

    # ---- breakeven: 新建方案 ----
    pg.click("nav a:has-text('回本测算')", timeout=6000)
    pg.wait_for_timeout(1200)
    dump(pg, "be-empty")
    try:
        pg.click("button:has-text('新建方案')", timeout=6000)
        pg.wait_for_timeout(800)
        # 填默认值保存
        pg.click("button:has-text('保存')", timeout=6000)
        pg.wait_for_timeout(2500)
        dump(pg, "be-after-create")
        print("be stat cards:", json.dumps(pg.eval_on_selector_all(".card.stat", "els => els.map(e=>e.innerText.replace(/\\n/g,' '))"), ensure_ascii=False))
    except Exception as e:
        print("breakeven create fail:", str(e)[:200])

    print("== ERRORS ==")
    seen = set()
    for e in errors:
        if e[:80] in seen: continue
        seen.add(e[:80]); print(e)
    b.close()
print("DONE")
