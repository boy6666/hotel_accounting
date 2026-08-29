# -*- coding: utf-8 -*-
"""pytest 根配置：让 sidecar/ 目录可被 import，并预生成测试盘。"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
