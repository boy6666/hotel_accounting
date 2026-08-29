package com.hotel.accounting.controller;

import com.hotel.accounting.common.ApiResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查（07 BE-01）：返回版本与 UP。免鉴权白名单。
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    @GetMapping
    public ApiResult<Map<String, Object>> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "up");
        m.put("version", "1.0.0");
        m.put("name", "hotel-accounting-backend");
        return ApiResult.ok(m);
    }
}
