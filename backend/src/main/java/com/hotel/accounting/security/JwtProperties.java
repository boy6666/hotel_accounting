package com.hotel.accounting.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 配置：secret 与 access/refresh 有效期。
 */
@ConfigurationProperties(prefix = "app.jwt")
public class JwtProperties {

    /** HMAC-SHA 密钥（UTF-8），长度须 >= 32 字节 */
    private String secret;

    /** access token 有效期（秒），契约 <= 2h，默认 7200 */
    private long accessTtlSeconds = 7200;

    /** refresh token 有效期（秒），默认 7 天 */
    private long refreshTtlSeconds = 604800;

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public long getAccessTtlSeconds() {
        return accessTtlSeconds;
    }

    public void setAccessTtlSeconds(long accessTtlSeconds) {
        this.accessTtlSeconds = accessTtlSeconds;
    }

    public long getRefreshTtlSeconds() {
        return refreshTtlSeconds;
    }

    public void setRefreshTtlSeconds(long refreshTtlSeconds) {
        this.refreshTtlSeconds = refreshTtlSeconds;
    }
}
