package com.hotel.accounting.controller;

import com.hotel.accounting.common.ApiResult;
import com.hotel.accounting.service.SettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 设置·基础数据（03 §13.1-13.4）：酒店配置 + 通用 KV（白名单）。房间见 RoomController。
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final SettingsService settingsService;

    public SettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping("/hotel")
    public ApiResult<Map<String, Object>> getHotel() {
        return ApiResult.ok(settingsService.getHotel());
    }

    @PutMapping("/hotel")
    public ApiResult<Map<String, Object>> updateHotel(@RequestBody SettingsService.HotelReq req) {
        return ApiResult.ok(settingsService.updateHotel(req));
    }

    @GetMapping("/kv")
    public ApiResult<Map<String, String>> getKv(@RequestParam String key) {
        return ApiResult.ok(settingsService.getKv(key));
    }

    @PutMapping("/kv")
    public ApiResult<Map<String, String>> putKv(@RequestBody KvReq req) {
        return ApiResult.ok(settingsService.putKv(req.getKey(), req.getValue()));
    }

    public static class KvReq {
        private String key;
        private String value;

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }
}
