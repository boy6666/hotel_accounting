# -*- coding: utf-8 -*-
"""日志：logs/sidecar-<date>.log，保留 14 天（TimedRotatingFileHandler）"""
import logging
import logging.handlers
import os

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LOG_DIR = os.path.join(BASE_DIR, "logs")

_FMT = "%(asctime)s %(levelname)s %(name)s %(message)s"


def _build(name: str) -> logging.Logger:
    os.makedirs(LOG_DIR, exist_ok=True)
    logger = logging.getLogger(name)
    if logger.handlers:  # 已初始化（如被重复 import / reload）
        return logger
    logger.setLevel(logging.INFO)

    fh = logging.handlers.TimedRotatingFileHandler(
        os.path.join(LOG_DIR, "sidecar.log"),
        when="midnight", interval=1, backupCount=14, encoding="utf-8",
    )
    fh.setFormatter(logging.Formatter(_FMT))
    fh.suffix = "%Y-%m-%d"  # 轮转后旧文件为 sidecar.log.2026-08-24（默认命名规则）

    sh = logging.StreamHandler()
    sh.setFormatter(logging.Formatter(_FMT))

    logger.addHandler(fh)
    logger.addHandler(sh)
    return logger


logger = _build("sidecar")
