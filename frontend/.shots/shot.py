# -*- coding: utf-8 -*-
import sys, os
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
from playwright.sync_api import sync_playwright

OUT = os.path.dirname(os.path.abspath(__file__))
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

with sync_playwright() as p:
    b = p.chromium.launch()
    pg = b.new_page(viewport={"width": 1440, "height": 900}, device_scale_factor=1)
    pg.goto(BASE, wait_until="networkidle", timeout=30000)
    pg.wait_for_timeout(500)
    # 登录（默认已填 admin/admin123）
    try:
        pg.click("button[type=submit]", timeout=8000)
        pg.wait_for_timeout(1800)
    except Exception as e:
        print("login btn issue:", str(e)[:120])
    # 等侧边栏出现
    pg.wait_for_selector("nav a", timeout=10000)
    pg.wait_for_timeout(600)
    sh = os.path.join(OUT, "00-login.png")
    pg.screenshot(path=sh)
    print("shot login")
    for slug, label in PAGES:
        try:
            pg.click(f"nav a:has-text('{label}')", timeout=6000)
            pg.wait_for_timeout(1600)
            pg.screenshot(path=os.path.join(OUT, f"{slug}.png"), full_page=False)
            print("shot", slug)
        except Exception as e:
            print("shot fail", slug, str(e)[:120])
    b.close()
print("DONE")
