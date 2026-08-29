# -*- coding: utf-8 -*-
"""开发用旁车桩（STUB）：模拟 FastAPI 旁车 14.1 /api/parse + 14.5 /api/health。

用途：主后端导入闭环（BE-08）在旁车未部署时也可端到端自验。
- 监听 127.0.0.1:8001（与旁车同端口契约）
- /api/parse：收到 {file_path, month} → 返回固定三表 JSON（月份用 2026-09）
- 读 file_path 校验文件存在（相对路径按主后端运行目录解析）

运行：python dev_stub_sidecar.py
"""
import os
from typing import Any, Dict

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

app = FastAPI(title="hotel-accounting-sidecar-stub")

# 主后端运行目录（mvn spring-boot:run 的工作目录）
BACKEND_DIR = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__))))


@app.get("/api/health")
def health() -> Dict[str, Any]:
    return {"ok": True, "status": "up"}


@app.post("/api/parse")
async def parse(req: Request) -> Dict[str, Any]:
    body = await req.json()
    file_path = body.get("file_path", "")
    month = body.get("month", "")
    # file_path 可能为相对路径（storage/uploads/...），按主后端目录解析
    if not os.path.isabs(file_path):
        file_path = os.path.normpath(os.path.join(BACKEND_DIR, file_path))
    if not os.path.exists(file_path):
        return JSONResponse(status_code=500, content={"code": 50000, "message": "文件不存在: " + file_path})
    print("[stub] parse called month=%s file=%s exists=%s" % (month, file_path, os.path.exists(file_path)))
    data = {
        "costs": [
            {"rowNo": 1, "rawName": "电费", "amount": 5000, "type": "fixed", "note": None},
            {"rowNo": 2, "rawName": "新菜式采购", "amount": 300, "type": "variable", "note": "测试新建"},
        ],
        "channels": [
            {"rawName": "携程", "nights": 40, "revenue": 15000, "note": None},
            {"rawName": "新渠道-本地生活", "nights": 5, "revenue": 2000, "note": None},
        ],
        "occupancy": [
            {"bizDate": "2026-09-01", "roomNos": ["201", "202"]},
            {"bizDate": "2026-09-02", "roomNos": ["201"]},
        ],
        "suggestions": {},
    }
    return {"code": 0, "message": "ok", "data": data}


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="127.0.0.1", port=8001, log_level="info")
