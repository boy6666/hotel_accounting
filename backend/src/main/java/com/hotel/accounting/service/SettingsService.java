package com.hotel.accounting.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hotel.accounting.common.BizException;
import com.hotel.accounting.mapper.AppSettingMapper;
import com.hotel.accounting.mapper.HotelConfigMapper;
import com.hotel.accounting.model.AppSetting;
import com.hotel.accounting.model.HotelConfig;
import com.hotel.accounting.util.AuditLogger;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 设置·基础数据（BE-02）：酒店配置单行 + 通用 KV（白名单）。
 */
@Service
public class SettingsService {

    public static final String KEY_DAYS_PER_MONTH = "default.daysPerMonth";
    public static final BigDecimal DEFAULT_DAYS_PER_MONTH = new BigDecimal("30.4");
    public static final BigDecimal DEFAULT_RATE = new BigDecimal("0.1000");

    private static final Set<String> KV_WHITELIST = Set.of(
            "llm.model", "llm.base_url", "system.template_ver",
            "default.daysPerMonth", "sidecar.base_url", "ui.theme"
    );

    private final HotelConfigMapper hotelConfigMapper;
    private final AppSettingMapper appSettingMapper;
    private final AuditLogger audit;

    public SettingsService(HotelConfigMapper hotelConfigMapper,
                           AppSettingMapper appSettingMapper,
                           AuditLogger audit) {
        this.hotelConfigMapper = hotelConfigMapper;
        this.appSettingMapper = appSettingMapper;
        this.audit = audit;
    }

    public Map<String, Object> getHotel() {
        HotelConfig cfg = getOrCreateConfig();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", cfg.getId());
        m.put("hotelName", cfg.getHotelName());
        m.put("city", cfg.getCity());
        m.put("defaultCommissionRate", cfg.getDefaultCommissionRate());
        m.put("daysPerMonth", getDaysPerMonth());
        return m;
    }

    public Map<String, Object> updateHotel(HotelReq req) {
        BigDecimal rate = req.getDefaultCommissionRate() == null ? DEFAULT_RATE : req.getDefaultCommissionRate();
        if (rate.compareTo(BigDecimal.ZERO) < 0 || rate.compareTo(BigDecimal.ONE) >= 0) {
            throw BizException.badRequest("defaultCommissionRate 必须满足 0 ≤ rate < 1");
        }
        HotelConfig cfg = getOrCreateConfig();
        if (req.getHotelName() != null) {
            cfg.setHotelName(req.getHotelName());
        }
        if (req.getCity() != null) {
            cfg.setCity(req.getCity());
        }
        if (req.getDefaultCommissionRate() != null) {
            cfg.setDefaultCommissionRate(rate);
        }
        hotelConfigMapper.updateById(cfg);
        if (req.getDaysPerMonth() != null) {
            if (req.getDaysPerMonth().compareTo(BigDecimal.ZERO) <= 0
                    || req.getDaysPerMonth().compareTo(new BigDecimal("366")) > 0) {
                throw BizException.badRequest("daysPerMonth 应在 (0, 366] 之间");
            }
            putSetting(KEY_DAYS_PER_MONTH, req.getDaysPerMonth().setScale(2, RoundingMode.HALF_UP).toPlainString());
        }
        audit.logAmount("UPDATE_HOTEL_CONFIG", "hotel_config#1", "defaultCommissionRate=" + rate);
        return getHotel();
    }

    private HotelConfig getOrCreateConfig() {
        HotelConfig cfg = hotelConfigMapper.selectById(1L);
        if (cfg == null) {
            cfg = new HotelConfig();
            cfg.setId(1L);
            cfg.setHotelName("我的酒店");
            cfg.setDefaultCommissionRate(DEFAULT_RATE);
            hotelConfigMapper.insert(cfg);
        }
        return cfg;
    }

    public BigDecimal getDaysPerMonth() {
        AppSetting s = appSettingMapper.selectOne(
                new LambdaQueryWrapper<AppSetting>().eq(AppSetting::getSkey, KEY_DAYS_PER_MONTH));
        if (s == null || s.getSvalue() == null || s.getSvalue().isBlank()) {
            return DEFAULT_DAYS_PER_MONTH;
        }
        try {
            return new BigDecimal(s.getSvalue());
        } catch (NumberFormatException e) {
            return DEFAULT_DAYS_PER_MONTH;
        }
    }

    public Map<String, String> getKv(String key) {
        if (!KV_WHITELIST.contains(key)) {
            throw BizException.badRequest("不允许访问的配置项: " + key);
        }
        AppSetting s = appSettingMapper.selectOne(
                new LambdaQueryWrapper<AppSetting>().eq(AppSetting::getSkey, key));
        return Map.of("key", key, "value", s == null ? "" : s.getSvalue());
    }

    public Map<String, String> putKv(String key, String value) {
        if (!KV_WHITELIST.contains(key)) {
            throw BizException.badRequest("不允许写入的配置项: " + key);
        }
        putSetting(key, value == null ? "" : value);
        return Map.of("key", key, "value", value == null ? "" : value);
    }

    private void putSetting(String key, String value) {
        AppSetting s = appSettingMapper.selectOne(
                new LambdaQueryWrapper<AppSetting>().eq(AppSetting::getSkey, key));
        if (s == null) {
            s = new AppSetting();
            s.setSkey(key);
            s.setSvalue(value);
            appSettingMapper.insert(s);
        } else {
            s.setSvalue(value);
            appSettingMapper.updateById(s);
        }
    }

    public static class HotelReq {
        private String hotelName;
        private String city;
        private BigDecimal defaultCommissionRate;
        private BigDecimal daysPerMonth;

        public String getHotelName() {
            return hotelName;
        }

        public void setHotelName(String hotelName) {
            this.hotelName = hotelName;
        }

        public String getCity() {
            return city;
        }

        public void setCity(String city) {
            this.city = city;
        }

        public BigDecimal getDefaultCommissionRate() {
            return defaultCommissionRate;
        }

        public void setDefaultCommissionRate(BigDecimal defaultCommissionRate) {
            this.defaultCommissionRate = defaultCommissionRate;
        }

        public BigDecimal getDaysPerMonth() {
            return daysPerMonth;
        }

        public void setDaysPerMonth(BigDecimal daysPerMonth) {
            this.daysPerMonth = daysPerMonth;
        }
    }
}
