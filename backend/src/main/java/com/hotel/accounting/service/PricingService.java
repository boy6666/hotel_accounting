package com.hotel.accounting.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hotel.accounting.common.BizException;
import com.hotel.accounting.mapper.PredictionResultMapper;
import com.hotel.accounting.mapper.PricingSuggestionMapper;
import com.hotel.accounting.mapper.PricingTierMapper;
import com.hotel.accounting.model.PredictionResult;
import com.hotel.accounting.model.PricingSuggestion;
import com.hotel.accounting.model.PricingTier;
import com.hotel.accounting.util.AuditLogger;
import com.hotel.accounting.util.HolidayUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 临近日逐日建议价引擎（BE-09 09-A，03 §10.5-10.7）。
 *
 * <p>引擎 v1 系数：按「预测入住率」档位基准价加权、四舍五入到整元——
 * {@code rate>=90 → ×1.06；85≤rate<90 → ×1.03；75≤rate<85 → ×1.00；
 * 60≤rate<75 → ×0.97；rate<60 → ×0.94；rate=null → ×1.00}。</p>
 *
 * <p>档位选择：先看是否有 {@code apply_days='holiday' 且日期落在 effective_from~effective_to} 的启用档位
 * （命中即节假日价，即使非法定节假日——如 2026-09-30 落在国庆档位区间），否则周末→weekend、平日→weekday
 * （均取 active、sort_order 最早）。结果 UPSERT {@code pricing_suggestion}（biz_date 唯一），
 * 已有 {@code source='manual'} 行跳过不覆盖（手改价锁定优先）。</p>
 */
@Service
public class PricingService {

    private final PricingSuggestionMapper pricingSuggestionMapper;
    private final PricingTierMapper pricingTierMapper;
    private final PredictionResultMapper predictionResultMapper;
    private final AuditLogger audit;

    public PricingService(PricingSuggestionMapper pricingSuggestionMapper,
                          PricingTierMapper pricingTierMapper,
                          PredictionResultMapper predictionResultMapper,
                          AuditLogger audit) {
        this.pricingSuggestionMapper = pricingSuggestionMapper;
        this.pricingTierMapper = pricingTierMapper;
        this.predictionResultMapper = predictionResultMapper;
        this.audit = audit;
    }

    /** 10.6 POST /api/pricing/suggestions/generate */
    @Transactional
    public Map<String, Object> generate(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw BizException.badRequest("from/to 不能为空");
        }
        if (from.isAfter(to)) {
            throw BizException.badRequest("from 不能晚于 to");
        }
        long span = ChronoUnit.DAYS.between(from, to) + 1;
        if (span > 62) {
            throw BizException.badRequest("建议价生成区间最多 62 天（当前 " + span + " 天）");
        }

        List<PricingTier> tiers = pricingTierMapper.selectList(null);
        Map<Long, String> tierName = tiers.stream()
                .collect(Collectors.toMap(PricingTier::getId, PricingTier::getName, (a, b) -> a));
        LocalDateTime now = LocalDateTime.now();

        List<Map<String, Object>> items = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            PricingSuggestion existing = findByDate(d);
            if (existing != null && "manual".equals(existing.getSource())) {
                // 手改价锁定优先：不覆盖，返回行 source 仍 'manual'
                items.add(toItem(d, existing, tierName));
                continue;
            }
            PricingTier tier = pickTier(tiers, d);
            if (tier == null) {
                Map<String, Object> e = baseItem(d);
                e.put("tierId", null);
                e.put("tierName", null);
                e.put("suggestedPrice", null);
                e.put("occupancyForecast", null);
                e.put("source", "none"); // 无档位，该日不落库（列表仍会返回此日）
                items.add(e);
                continue;
            }
            BigDecimal rate = latestDailyRate(d);
            BigDecimal factor = factorOf(rate);
            BigDecimal price = tier.getBasePrice().multiply(factor).setScale(0, RoundingMode.HALF_UP);

            if (existing == null) {
                PricingSuggestion row = new PricingSuggestion();
                row.setBizDate(d);
                row.setTierId(tier.getId());
                row.setSuggestedPrice(price);
                row.setOccupancyForecast(rate);
                row.setIsWeekend(HolidayUtil.isWeekend(d) ? 1 : 0);
                row.setSource("engine");
                row.setGeneratedAt(now);
                pricingSuggestionMapper.insert(row);
            } else {
                existing.setTierId(tier.getId());
                existing.setSuggestedPrice(price);
                existing.setOccupancyForecast(rate);
                existing.setIsWeekend(HolidayUtil.isWeekend(d) ? 1 : 0);
                existing.setSource("engine");
                existing.setGeneratedAt(now);
                pricingSuggestionMapper.updateById(existing);
            }
            items.add(toItem(d, tier.getId(), tier.getName(), price, rate));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generated", items.size());
        out.put("items", items);
        audit.logAmount("SUSGGESTIONS_GENERATE", "from=" + from + " to=" + to,
                "days=" + items.size());
        return out;
    }

    /** 10.5 GET /api/pricing/suggestions?from&to（未生成的日期不出现）。 */
    public Map<String, Object> list(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw BizException.badRequest("from/to 不能为空");
        }
        if (from.isAfter(to)) {
            throw BizException.badRequest("from 不能晚于 to");
        }
        Map<Long, String> tierName = pricingTierMapper.selectList(null).stream()
                .collect(Collectors.toMap(PricingTier::getId, PricingTier::getName, (a, b) -> a));
        List<PricingSuggestion> rows = pricingSuggestionMapper.selectList(
                new LambdaQueryWrapper<PricingSuggestion>()
                        .between(PricingSuggestion::getBizDate, from, to)
                        .orderByAsc(PricingSuggestion::getBizDate));
        List<Map<String, Object>> items = rows.stream()
                .map(r -> toItem(r.getBizDate(), r, tierName))
                .collect(Collectors.toList());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        return out;
    }

    /** 10.7 PUT /api/pricing/suggestions/{bizDate}：人工改价并锁定（source=manual）。 */
    @Transactional
    public Map<String, Object> manualPut(LocalDate bizDate, BigDecimal suggestedPrice) {
        if (bizDate == null) {
            throw BizException.badRequest("bizDate 必填");
        }
        if (suggestedPrice == null || suggestedPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw BizException.badRequest("suggestedPrice 必须 > 0");
        }
        PricingSuggestion row = findByDate(bizDate);
        boolean isNew = row == null;
        if (isNew) {
            row = new PricingSuggestion();
            row.setBizDate(bizDate);
            row.setTierId(null);
            row.setOccupancyForecast(null);
            row.setIsWeekend(HolidayUtil.isWeekend(bizDate) ? 1 : 0);
        }
        row.setSuggestedPrice(suggestedPrice);
        row.setSource("manual");
        row.setGeneratedAt(LocalDateTime.now());
        if (isNew) {
            pricingSuggestionMapper.insert(row);
        } else {
            pricingSuggestionMapper.updateById(row);
        }
        Map<Long, String> tierName = pricingTierMapper.selectList(null).stream()
                .collect(Collectors.toMap(PricingTier::getId, PricingTier::getName, (a, b) -> a));
        audit.logAmount("SUGGESTION_MANUAL", "date=" + bizDate, "price=" + suggestedPrice);
        return toItem(bizDate, row, tierName);
    }

    // ------------------------------------------------------------------
    // 引擎内部
    // ------------------------------------------------------------------

    /** 档位选择：节假日（apply_days='holiday' 且日期在 effective 区间内、active）→ 周末 → 平日。 */
    private PricingTier pickTier(List<PricingTier> tiers, LocalDate d) {
        for (PricingTier t : tiers) {
            if (isActive(t) && "holiday".equals(t.getApplyDays()) && within(t, d)) {
                return t;
            }
        }
        String want = HolidayUtil.isWeekend(d) ? "weekend" : "weekday";
        for (PricingTier t : tiers) {
            if (isActive(t) && want.equals(t.getApplyDays())) {
                return t;
            }
        }
        return null;
    }

    private boolean isActive(PricingTier t) {
        return t.getActive() == null || t.getActive() == 1;
    }

    private boolean within(PricingTier t, LocalDate d) {
        if (t.getEffectiveFrom() != null && d.isBefore(t.getEffectiveFrom())) {
            return false;
        }
        return t.getEffectiveTo() == null || !d.isAfter(t.getEffectiveTo());
    }

    /** 引擎系数表（v1）：按预测入住率对档位基准价加权，四舍五入到整元。 */
    static BigDecimal factorOf(BigDecimal rate) {
        if (rate == null) {
            return BigDecimal.ONE;
        }
        double r = rate.doubleValue();
        if (r >= 90) {
            return new BigDecimal("1.06");
        }
        if (r >= 85) {
            return new BigDecimal("1.03");
        }
        if (r >= 75) {
            return BigDecimal.ONE;
        }
        if (r >= 60) {
            return new BigDecimal("0.97");
        }
        return new BigDecimal("0.94");
    }

    /** 该 bizDate 的预测入住率（prediction_result 日粒度最近一版），无则 null（引擎按 ×1.00）。 */
    private BigDecimal latestDailyRate(LocalDate d) {
        List<PredictionResult> rows = predictionResultMapper.selectList(
                new LambdaQueryWrapper<PredictionResult>()
                        .eq(PredictionResult::getTargetType, "daily")
                        .eq(PredictionResult::getTarget, d.toString())
                        .orderByDesc(PredictionResult::getGeneratedAt));
        // 优先 occupancy_rate 指标；无则用最近一版任意指标
        for (PredictionResult r : rows) {
            if ("occupancy_rate".equals(r.getMetric())) {
                return r.getPredictedValue();
            }
        }
        return rows.isEmpty() ? null : rows.get(0).getPredictedValue();
    }

    private PricingSuggestion findByDate(LocalDate d) {
        return pricingSuggestionMapper.selectOne(
                new LambdaQueryWrapper<PricingSuggestion>().eq(PricingSuggestion::getBizDate, d));
    }

    private Map<String, Object> toItem(LocalDate d, PricingSuggestion row, Map<Long, String> tierName) {
        Map<String, Object> e = baseItem(d);
        e.put("tierId", row.getTierId());
        e.put("tierName", row.getTierId() == null ? null : tierName.get(row.getTierId()));
        e.put("suggestedPrice", row.getSuggestedPrice());
        e.put("occupancyForecast", row.getOccupancyForecast());
        e.put("source", row.getSource());
        return e;
    }

    private Map<String, Object> toItem(LocalDate d, Long tierId, String tierName,
                                       BigDecimal price, BigDecimal rate) {
        Map<String, Object> e = baseItem(d);
        e.put("tierId", tierId);
        e.put("tierName", tierName);
        e.put("suggestedPrice", price);
        e.put("occupancyForecast", rate);
        e.put("source", "engine");
        return e;
    }

    private Map<String, Object> baseItem(LocalDate d) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("bizDate", d);
        e.put("isWeekend", HolidayUtil.isWeekend(d));
        return e;
    }
}
