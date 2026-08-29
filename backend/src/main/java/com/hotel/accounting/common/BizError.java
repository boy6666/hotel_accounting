package com.hotel.accounting.common;

import org.springframework.http.HttpStatus;

/**
 * 业务错误码（与 03-API文档 §3 一致）。每个错误码对应建议的 HTTP 状态。
 */
public enum BizError {

    OK(0, HttpStatus.OK, "ok"),
    BAD_REQUEST(40000, HttpStatus.BAD_REQUEST, "参数/请求体校验失败"),
    UNAUTHORIZED(40100, HttpStatus.UNAUTHORIZED, "未登录或令牌无效"),
    TOKEN_EXPIRED(40101, HttpStatus.UNAUTHORIZED, "令牌过期"),
    FORBIDDEN(40300, HttpStatus.FORBIDDEN, "无权限"),
    NOT_FOUND(40400, HttpStatus.NOT_FOUND, "资源不存在"),
    CONFLICT(40900, HttpStatus.CONFLICT, "冲突"),
    INTERNAL_ERROR(50000, HttpStatus.INTERNAL_SERVER_ERROR, "服务端内部错误"),
    SIDECAR_UNAVAILABLE(50100, HttpStatus.SERVICE_UNAVAILABLE, "智能服务暂不可用"),
    DEEPSEEK_FAILED(50200, HttpStatus.SERVICE_UNAVAILABLE, "DeepSeek 调用失败"),
    FILE_PARSE_FAILED(50300, HttpStatus.UNPROCESSABLE_ENTITY, "文件解析失败 / 模板格式不符");

    private final int code;
    private final HttpStatus httpStatus;
    private final String defaultMessage;

    BizError(int code, HttpStatus httpStatus, String defaultMessage) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public int code() {
        return code;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
