package com.hotel.accounting.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hotel.accounting.common.BizException;
import com.hotel.accounting.mapper.PriceCalcScenarioMapper;
import com.hotel.accounting.model.PriceCalcScenario;
import com.hotel.accounting.util.AuditLogger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 目标倒推（BE-09 09-B，03 §10.8-10.10）。
 *
 * <p>{@code targetPrice = targetRevenue ÷ (roomCount × daysPerMonth × occupancy%)}，round(2)；
 * （可售房间数默认 = room 表 enabled=1 计数，由前端 what-if 时传入假设值）。纯计算不落库；
 * 保存场景落 {@code price_calc_scenario}。</p>
 */
@Service
public class PricingCalcService {

    private final PriceCalcScenarioMapper scenarioMapper;
    private final SettingsService settingsService;
    private final RecalcService recalcService;
    private final AuditLogger audit;

    public PricingCalcService(PriceCalcScenarioMapper scenarioMapper,
                              SettingsService settingsService,
                              RecalcService recalcService,
                              AuditLogger audit) {
        this.scenarioMapper = scenarioMapper;
        this.settingsService = settingsService;
        this.recalcService = recalcService;
        this.audit = audit;
    }

    /** 10.8 GET /api/pricing/calc/target：纯计算不落库。roomCount 缺省 = room 表 enabled=1 计数。 */
    public Map<String, Object> target(BigDecimal targetRevenue, BigDecimal targetOccupancy,
                                      Integer roomCount, BigDecimal daysPerMonth) {
        BigDecimal dpm = daysPerMonth == null ? settingsService.getDaysPerMonth() : daysPerMonth;
        if (roomCount == null) {
            roomCount = recalcService.enabledRoomCount();
        }
        validate(targetRevenue, targetOccupancy, roomCount, dpm);

        BigDecimal occ = targetOccupancy.divide(new BigDecimal("100"), 10, RoundingMode.HALF_UP);
        BigDecimal bedrooms = BigDecimal.valueOf(roomCount).multiply(dpm).multiply(occ);
        BigDecimal targetPrice = targetRevenue.divide(bedrooms, 2, RoundingMode.HALF_UP);

        Map<String, Object> monthly = new LinkedHashMap<>();
        monthly.put("revenue", targetRevenue);
        monthly.put("nights", bedrooms);
        monthly.put("adrNeed", targetPrice);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("targetPrice", targetPrice);
        out.put("monthly", monthly);
        return out;
    }

    /** 10.9 POST /api/pricing/calc/scenarios：保存倒推参数/结果。 */
    @Transactional
    public Map<String, Object> saveScenario(CalcReq req) {
        if (req == null) {
            throw BizException.badRequest("请求体不能为空");
        }
        BigDecimal dpm = settingsService.getDaysPerMonth();
        validate(req.getTargetRevenue(), req.getTargetOccupancy(), req.getRoomCount(), dpm);

        BigDecimal result = req.getResultPrice();
        if (result == null) {
            BigDecimal occ = req.getTargetOccupancy().divide(new BigDecimal("100"), 10, RoundingMode.HALF_UP);
            BigDecimal bedrooms = BigDecimal.valueOf(req.getRoomCount()).multiply(dpm).multiply(occ);
            result = req.getTargetRevenue().divide(bedrooms, 2, RoundingMode.HALF_UP);
        }
        PriceCalcScenario s = new PriceCalcScenario();
        s.setName(req.getName() == null || req.getName().isBlank() ? null : req.getName().trim());
        s.setTargetRevenue(req.getTargetRevenue());
        s.setTargetOccupancy(req.getTargetOccupancy());
        s.setRoomCount(req.getRoomCount());
        s.setResultPrice(result.setScale(2, RoundingMode.HALF_UP));
        scenarioMapper.insert(s);
        audit.log("PRICING_CALC_SAVE", "targetRevenue=" + req.getTargetRevenue()
                + " occ=" + req.getTargetOccupancy() + " rooms=" + req.getRoomCount()
                + " price=" + result);
        return toMap(s);
    }

    /** 10.10 GET /api/pricing/calc/scenarios：最近 20 条（id 倒序）。 */
    public Map<String, Object> listScenarios() {
        List<PriceCalcScenario> rows = scenarioMapper.selectList(
                new LambdaQueryWrapper<PriceCalcScenario>()
                        .orderByDesc(PriceCalcScenario::getId)
                        .last("LIMIT 20"));
        List<Map<String, Object>> items = new ArrayList<>();
        for (PriceCalcScenario s : rows) {
            items.add(toMap(s));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        return out;
    }

    private Map<String, Object> toMap(PriceCalcScenario s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("name", s.getName());
        m.put("targetRevenue", s.getTargetRevenue());
        m.put("targetOccupancy", s.getTargetOccupancy());
        m.put("roomCount", s.getRoomCount());
        m.put("resultPrice", s.getResultPrice());
        m.put("createdAt", s.getCreatedAt() == null ? null : s.getCreatedAt().toString().replace('T', ' '));
        return m;
    }

    private void validate(BigDecimal targetRevenue, BigDecimal targetOccupancy,
                          Integer roomCount, BigDecimal dpm) {
        if (targetRevenue == null || targetRevenue.compareTo(BigDecimal.ZERO) <= 0) {
            throw BizException.badRequest("targetRevenue 必须 > 0");
        }
        if (targetOccupancy == null || targetOccupancy.compareTo(BigDecimal.ZERO) <= 0
                || targetOccupancy.compareTo(new BigDecimal("100")) > 0) {
            throw BizException.badRequest("targetOccupancy 必须在 (0,100] 之间（%）");
        }
        if (roomCount == null || roomCount < 1) {
            throw BizException.badRequest("roomCount 必须 >= 1");
        }
        if (dpm == null || dpm.compareTo(BigDecimal.ZERO) <= 0) {
            throw BizException.badRequest("daysPerMonth 必须 > 0");
        }
    }

    public static class CalcReq {
        private String name;
        private BigDecimal targetRevenue;
        private BigDecimal targetOccupancy;
        private Integer roomCount;
        private BigDecimal resultPrice;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public BigDecimal getTargetRevenue() {
            return targetRevenue;
        }

        public void setTargetRevenue(BigDecimal targetRevenue) {
            this.targetRevenue = targetRevenue;
        }

        public BigDecimal getTargetOccupancy() {
            return targetOccupancy;
        }

        public void setTargetOccupancy(BigDecimal targetOccupancy) {
            this.targetOccupancy = targetOccupancy;
        }

        public Integer getRoomCount() {
            return roomCount;
        }

        public void setRoomCount(Integer roomCount) {
            this.roomCount = roomCount;
        }

        public BigDecimal getResultPrice() {
            return resultPrice;
        }

        public void setResultPrice(BigDecimal resultPrice) {
            this.resultPrice = resultPrice;
        }
    }
}
