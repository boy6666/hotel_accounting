package com.hotel.accounting.security;

import org.springframework.http.HttpStatus;

/**
 * JWT 认证异常：40100 未登录/令牌无效；40101 令牌过期（前端据此自动刷新后重放）。
 */
public class JwtAuthException extends RuntimeException {

    private final int code;
    private final HttpStatus httpStatus;

    public JwtAuthException(int code, HttpStatus httpStatus, String message) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public int getCode() {
        return code;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
