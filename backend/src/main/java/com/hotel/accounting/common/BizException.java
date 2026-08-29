package com.hotel.accounting.common;

import org.springframework.http.HttpStatus;

/**
 * 业务异常。带业务错误码（code）与用户可读中文 message；由全局异常处理器映射到信封 + HTTP 状态。
 */
public class BizException extends RuntimeException {

    private final int code;
    private final HttpStatus httpStatus;

    public BizException(int code, HttpStatus httpStatus, String message) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public BizException(BizError error, String message) {
        this(error.code(), error.httpStatus(), message);
    }

    public static BizException badRequest(String message) {
        return new BizException(BizError.BAD_REQUEST, message);
    }

    public static BizException unauthorized(String message) {
        return new BizException(BizError.UNAUTHORIZED, message);
    }

    public static BizException tokenExpired(String message) {
        return new BizException(BizError.TOKEN_EXPIRED, message);
    }

    public static BizException notFound(String message) {
        return new BizException(BizError.NOT_FOUND, message);
    }

    public static BizException conflict(String message) {
        return new BizException(BizError.CONFLICT, message);
    }

    public static BizException internal(String message) {
        return new BizException(BizError.INTERNAL_ERROR, message);
    }

    public static BizException sidecarUnavailable(String message) {
        return new BizException(BizError.SIDECAR_UNAVAILABLE, message);
    }

    public static BizException fileParseFailed(String message) {
        return new BizException(BizError.FILE_PARSE_FAILED, message);
    }

    public int getCode() {
        return code;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
