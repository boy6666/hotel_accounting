package com.hotel.accounting.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 旁车（FastAPI，仅 127.0.0.1）连接配置。
 */
@ConfigurationProperties(prefix = "app.sidecar")
public class SidecarProperties {

    private String baseUrl = "http://127.0.0.1:8001";
    private long timeoutMs = 5000;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }
}
