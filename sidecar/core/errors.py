# -*- coding: utf-8 -*-
"""统一业务错误与信封构造（对齐 docs/03-API文档.md §1.2 / §3 错误码）。

旁车只负责以 code/message 把错误说出来：
  - 50300 文件解析失败 / 模板格式不符（HTTP 422），主后端直接透传展示行号/原因；
  - 40000 参数校验失败（HTTP 400）；
  - 50000 服务端内部异常（HTTP 500）——由主后端统一兜底映射 50100 降级。
"""


class ApiError(Exception):
    """带 HTTP 状态码与业务 code/message 的业务异常。"""

    def __init__(self, code: int, message: str, http_status: int = 200):
        super().__init__(message)
        self.code = code
        self.message = message
        self.http_status = http_status


# 错误码常量
OK = 0
PARAM_ERROR = 40000
PARSE_ERROR = 50300
INTERNAL_ERROR = 50000

# HTTP 状态码
HTTP_OK = 200
HTTP_BAD_REQUEST = 400
HTTP_UNPROCESSABLE = 422
HTTP_INTERNAL = 500


def ok(data):
    """成功信封：{code:0, message:'ok', data}"""
    return {"code": OK, "message": "ok", "data": data}


def err(code: int, message: str, http_status: int = HTTP_OK):
    """错误信封字面量（供直接返回时用）。
    注意：正常流程建议直接 raise ApiError，由全局异常处理器统一转 JSONResponse。
    """
    return {"code": code, "message": message, "data": None}
