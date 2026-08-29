package com.hotel.accounting.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hotel.accounting.client.SidecarClient;
import com.hotel.accounting.common.BizException;
import com.hotel.accounting.mapper.DailyOccupancyMapper;
import com.hotel.accounting.mapper.HotelConfigMapper;
import com.hotel.accounting.mapper.MonthlySummaryMapper;
import com.hotel.accounting.mapper.PredictionResultMapper;
import com.hotel.accounting.model.DailyOccupancy;
import com.hotel.accounting.model.HotelConfig;
import com.hotel.accounting.model.MonthlySummary;
import com.hotel.accounting.model.PredictionResult;
import com.hotel.accounting.util.AuditLogger;
import com.hotel.accounting.util.HolidayUtil;
import com.hotel.accounting.util.Months;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 预测生成 + 落地 + 对旁车（BE-09 09-C，03 §10.11-10.13 / §14.3-14.4）。
 *
 * <p>预测链路：主后端汇总历史（近 12 月该指标列，空月跳过，不足 3 月 → 40000）+ 近 30 日入住率窗口
 * + 目标月节假日 + 城市 → 调旁车 {@code /api/predict} → 成功可选调 {@code /api/llm/interpret}
 * （只送聚合摘要，不送明细/身份字段）→ 落 {@code prediction_result}。</p>
 *
 * <p><b>降级铁律</b>：旁车挂/超时/非 0 信封 → 主后端纯统计兜底
 * （近 3 月加权均值 × 目标月节假日系数[命中 ≥2 天 ×1.05，否则 ×1.0]，
 * 置信区间 ±10%），{@code engine='statistical'}、{@code llmInterpretation=null}、{@code degraded=true}，
 * 仍返回 200（响应 data.degraded 标记前端降级提示）。LLM 解读失败不阻塞。</p>
 */
@Service
public class PredictionService {

    private static final Logger log = LoggerFactory.getLogger(PredictionService.class);

    private static final Set<String> METRICS = Set.of("revenue", "nights", "occupancy_rate", "adr", "price");
    private static final Map<String, String> METRIC_LABELS = Map.of(
            "revenue", "收入", "nights", "间夜", "occupancy_rate", "入住率",
            "adr", "ADR", "price", "均价");
    private static final int MAX_HISTORY_MONTHS = 12;
    private static final int MIN_HISTORY_MONTHS = 3;

    private final PredictionResultMapper predictionResultMapper;
    private final MonthlySummaryMapper monthlySummaryMapper;
    private final DailyOccupancyMapper dailyOccupancyMapper;
    private final HotelConfigMapper hotelConfigMapper;
    private final SidecarClient sidecarClient;
    private final AuditLogger audit;

    public PredictionService(PredictionResultMapper predictionResultMapper,
                             MonthlySummaryMapper monthlySummaryMapper,
                             DailyOccupancyMapper dailyOccupancyMapper,
                             HotelConfigMapper hotelConfigMapper,
                             SidecarClient sidecarClient,
                             AuditLogger audit) {
        this.predictionResultMapper = predictionResultMapper;
        this.monthlySummaryMapper = monthlySummaryMapper;
        this.dailyOccupancyMapper = dailyOccupancyMapper;
        this.hotelConfigMapper = hotelConfigMapper;
        this.sidecarClient = sidecarClient;
        this.audit = audit;
    }

    /** 10.11 POST /api/prediction/generate */
    @Transactional
    public Map<String, Object> generate(String month, String metric) {
        YearMonth ym = Months.require(month);
        if (metric == null || !METRICS.contains(metric)) {
            throw BizException.badRequest("metric 必须是 revenue/nights/occupancy_rate/adr/price");
        }

        // 1) 汇总历史：近 12 个月该指标列（目标月之前），空月跳过；不足 3 个月 → 40000
        List<SumPt> history = history(ym, metric);
        if (history.size() < MIN_HISTORY_MONTHS) {
            throw BizException.badRequest("历史数据不足（近 12 个月有效数据 < 3 个月），无法预测。请先有至少 3 个月的月度数据。");
        }

        // 2) 近 30 日入住率窗口 {date, rate}
        List<Map<String, Object>> occupancyWindow = occupancyWindow();

        // 3) 目标月节假日 + 城市（天气旁车侧可空降级）
        List<String> holidays = holidays(ym);
        HotelConfig cfg = hotelConfigMapper.selectById(1L);
        String city = cfg == null ? null : cfg.getCity();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("target", month);
        body.put("metric", metric);
        List<Map<String, Object>> histBody = new ArrayList<>();
        history.forEach(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("month", p.month);
            m.put("value", p.value);
            histBody.add(m);
        });
        body.put("history", histBody);
        body.put("occupancyWindow", occupancyWindow);
        body.put("city", city);
        body.put("economicHolidays", holidays);

        BigDecimal value;
        BigDecimal low;
        BigDecimal high;
        String engine;
        String modelVersion = null;
        String llmText = null;
        boolean degraded = false;

        try {
            Map<String, Object> data = sidecarClient.predict(body);
            value = dec(data.get("predictedValue"));
            engine = str(data.get("engine"));
            if (!"hybrid".equals(engine)) {
                engine = "statistical";
            }
            low = decOrNull(data.get("confidenceLow"));
            high = decOrNull(data.get("confidenceHigh"));
            modelVersion = firstNonNull(str(data.get("model_version")), str(data.get("modelVersion")));
            // LLM 解读（非阻塞）：只送聚合摘要
            try {
                Map<String, Object> ib = new LinkedHashMap<>();
                ib.put("metric", metric);
                ib.put("predictedValue", value);
                ib.put("historyHeadline", headline(history, metric));
                ib.put("ask", "解读趋势并给出定价/经营建议，200字内");
                llmText = sidecarClient.llmInterpret(ib);
            } catch (BizException e) {
                log.warn("LLM 解读不可用，置 null 不阻塞: {}", e.getMessage());
                llmText = null;
            }
        } catch (BizException e) {
            // 旁车挂/超时 → 纯统计兜底，仍 200 + degraded
            log.warn("旁车预测不可用（{}），降级纯统计兜底", e.getMessage());
            degraded = true;
            engine = "statistical";
            value = statisticalFallback(history, ym);
            low = value.multiply(new BigDecimal("0.90")).setScale(2, RoundingMode.HALF_UP);
            high = value.multiply(new BigDecimal("1.10")).setScale(2, RoundingMode.HALF_UP);
            llmText = null;
            modelVersion = "main-backend-statistical-v1";
        }

        // 落库 prediction_result（monthly）
        PredictionResult pr = new PredictionResult();
        pr.setTargetType("monthly");
        pr.setTarget(month);
        pr.setMetric(metric);
        pr.setPredictedValue(value);
        pr.setEngine(engine);
        pr.setModelVersion(modelVersion);
        pr.setLlmInterpretation(llmText);
        pr.setConfidenceLow(low);
        pr.setConfidenceHigh(high);
        pr.setGeneratedAt(java.time.LocalDateTime.now());
        predictionResultMapper.insert(pr);
        audit.logAmount("PREDICTION_GENERATE", "target=" + month + " metric=" + metric
                + " degraded=" + degraded, "predicted=" + value);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", pr.getId());
        out.put("target", month);
        out.put("predictedValue", value);
        out.put("engine", engine);
        out.put("confidenceLow", low);
        out.put("confidenceHigh", high);
        out.put("llmInterpretation", llmText);
        out.put("degraded", degraded);
        return out;
    }

    /** 10.12 GET /api/prediction/results?target=&metric=（monthly 历史，目标月降序）。 */
    public Map<String, Object> results(String target, String metric) {
        if (metric != null && !METRICS.contains(metric)) {
            throw BizException.badRequest("metric 必须是 revenue/nights/occupancy_rate/adr/price");
        }
        if (target == null || target.isBlank()) {
            // target 可省略：取最近一个月
            List<String> months = predictionResultMapper.selectList(
                            new LambdaQueryWrapper<PredictionResult>()
                                    .eq(PredictionResult::getTargetType, "monthly"))
                    .stream().map(PredictionResult::getTarget).distinct().sorted().toList();
            if (!months.isEmpty()) {
                target = months.get(months.size() - 1);
            }
        }
        LambdaQueryWrapper<PredictionResult> qw = new LambdaQueryWrapper<PredictionResult>()
                .eq(PredictionResult::getTargetType, "monthly");
        if (target != null && !target.isBlank()) {
            Months.require(target);
            qw.eq(PredictionResult::getTarget, target);
        }
        if (metric != null) {
            qw.eq(PredictionResult::getMetric, metric);
        }
        qw.orderByDesc(PredictionResult::getGeneratedAt).orderByDesc(PredictionResult::getId);
        List<PredictionResult> rows = predictionResultMapper.selectList(qw);

        List<Map<String, Object>> items = new ArrayList<>();
        rows.forEach(r -> items.add(toResultItem(r)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("target", target);
        out.put("items", items);
        return out;
    }

    /** 10.13 GET /api/prediction/daily?date=&month=（日粒度，建议价引擎联动）。 */
    public Map<String, Object> daily(LocalDate date, String month) {
        YearMonth ym;
        if (date != null) {
            ym = YearMonth.from(date);
        } else if (month != null && !month.isBlank()) {
            ym = Months.require(month);
        } else {
            ym = YearMonth.now();
        }

        List<Map<String, Object>> items = new ArrayList<>();
        for (int d = 1; d <= ym.lengthOfMonth(); d++) {
            LocalDate day = ym.atDay(d);
            PredictionResult cached = latestDaily(day);
            BigDecimal rate;
            if (cached != null) {
                rate = cached.getPredictedValue();
            } else {
                rate = computeDailyRate(day);
                if (rate != null) {
                    // 缓存进 prediction_result（target_type='daily'，幂等：仅无缓存时落）
                    PredictionResult pr = new PredictionResult();
                    pr.setTargetType("daily");
                    pr.setTarget(day.toString());
                    pr.setMetric("occupancy_rate");
                    pr.setPredictedValue(rate);
                    pr.setEngine("statistical");
                    pr.setModelVersion("main-backend-calendar-v1");
                    pr.setGeneratedAt(java.time.LocalDateTime.now());
                    predictionResultMapper.insert(pr);
                }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", day);
            m.put("rate", rate);
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("month", ym.format(DateTimeFormatter.ofPattern("uuuu-MM")));
        out.put("items", items);
        return out;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private record SumPt(String month, BigDecimal value) {
    }

    /** 近 12 个月（目标月之前）该指标的历史；空月跳过。price 指标用 adr 列。 */
    private List<SumPt> history(YearMonth target, String metric) {
        Function<MonthlySummary, BigDecimal> pick;
        switch (metric) {
            case "revenue" -> pick = MonthlySummary::getRevenue;
            case "nights" -> pick = s -> BigDecimal.valueOf(s.getNights() == null ? 0 : s.getNights());
            case "occupancy_rate" -> pick = MonthlySummary::getOccupancyRate;
            case "adr", "price" -> pick = MonthlySummary::getAdr;
            default -> throw BizException.badRequest("metric 不合法: " + metric);
        }
        List<MonthlySummary> rows = monthlySummaryMapper.selectList(
                new LambdaQueryWrapper<MonthlySummary>()
                        .orderByAsc(MonthlySummary::getMonth));
        List<SumPt> out = new ArrayList<>();
        for (MonthlySummary s : rows) {
            String m = s.getMonth();
            if (m == null) {
                continue;
            }
            YearMonth ym = Months.parseOrNull(m);
            if (ym == null || !ym.isBefore(target)) {
                continue;
            }
            BigDecimal v = pick.apply(s);
            if (v != null) {
                out.add(new SumPt(m, v));
            }
        }
        int from = Math.max(0, out.size() - MAX_HISTORY_MONTHS);
        return out.subList(from, out.size());
    }

    /** 近 30 日 daily_occupancy 入住率窗口 [{date, rate}]（date 降序，日粒度，供旁车模型）。 */
    private List<Map<String, Object>> occupancyWindow() {
        List<DailyOccupancy> rows = dailyOccupancyMapper.selectList(
                new LambdaQueryWrapper<DailyOccupancy>()
                        .orderByDesc(DailyOccupancy::getBizDate)
                        .last("LIMIT 30"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (DailyOccupancy r : rows) {
            if (r.getTotalRooms() == null || r.getTotalRooms() <= 0
                    || r.getOccupiedRooms() == null || r.getBizDate() == null) {
                continue;
            }
            BigDecimal rate = BigDecimal.valueOf(r.getOccupiedRooms() * 100.0 / r.getTotalRooms())
                    .setScale(2, RoundingMode.HALF_UP);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", r.getBizDate().toString());
            m.put("rate", rate);
            out.add(m);
        }
        return out;
    }

    /** 目标月内法定休假日（HolidayUtil 内置 2026 表）。 */
    private List<String> holidays(YearMonth ym) {
        List<String> out = new ArrayList<>();
        for (int d = 1; d <= ym.lengthOfMonth(); d++) {
            LocalDate day = ym.atDay(d);
            if (HolidayUtil.isHoliday(day)) {
                out.add(day.toString());
            }
        }
        return out;
    }

    /** 旁车挂：近 3 月加权均值（0.5/0.8/1.0）× 目标月节假日系数（≥2 天 ×1.05）。 */
    private BigDecimal statisticalFallback(List<SumPt> history, YearMonth ym) {
        int n = history.size();
        int k = Math.min(3, n);
        double[] w = {0.5, 0.8, 1.0};
        BigDecimal sum = BigDecimal.ZERO;
        BigDecimal wSum = BigDecimal.ZERO;
        for (int i = 0; i < k; i++) {
            SumPt p = history.get(n - k + i);
            BigDecimal wi = BigDecimal.valueOf(w[i]);
            sum = sum.add(p.value().multiply(wi));
            wSum = wSum.add(wi);
        }
        BigDecimal mean = sum.divide(wSum, 6, RoundingMode.HALF_UP);
        long hd = holidays(ym).size();
        BigDecimal factor = hd >= 2 ? new BigDecimal("1.05") : BigDecimal.ONE;
        return mean.multiply(factor).setScale(2, RoundingMode.HALF_UP);
    }

    /** 聚合摘要文案（近 N 月 X → Y，环比 ±Z%），不含明细/身份字段。 */
    private String headline(List<SumPt> history, String metric) {
        BigDecimal first = history.get(0).value();
        BigDecimal last = history.get(history.size() - 1).value();
        BigDecimal delta = null;
        if (first != null && first.compareTo(BigDecimal.ZERO) != 0 && last != null) {
            delta = last.subtract(first).multiply(new BigDecimal("100"))
                    .divide(first, 1, RoundingMode.HALF_UP);
        }
        String label = METRIC_LABELS.getOrDefault(metric, metric);
        StringBuilder sb = new StringBuilder("近")
                .append(history.size()).append("月").append(label).append(" ")
                .append(first == null ? "-" : first.setScale(0, RoundingMode.HALF_UP).toPlainString())
                .append("→").append(last == null ? "-" : last.setScale(0, RoundingMode.HALF_UP).toPlainString());
        if (delta != null) {
            String sign = delta.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "";
            sb.append("，环比 ").append(sign).append(delta).append("%");
        }
        return sb.toString();
    }

    private BigDecimal computeDailyRate(LocalDate day) {
        List<DailyOccupancy> rows = dailyOccupancyMapper.selectList(
                new LambdaQueryWrapper<DailyOccupancy>()
                        .lt(DailyOccupancy::getBizDate, day)
                        .orderByDesc(DailyOccupancy::getBizDate)
                        .last("LIMIT 30"));
        BigDecimal sum = BigDecimal.ZERO;
        int cnt = 0;
        for (DailyOccupancy r : rows) {
            if (r.getTotalRooms() == null || r.getTotalRooms() <= 0 || r.getOccupiedRooms() == null) {
                continue;
            }
            sum = sum.add(BigDecimal.valueOf(r.getOccupiedRooms() * 100.0 / r.getTotalRooms()));
            cnt++;
        }
        if (cnt == 0) {
            return null;
        }
        BigDecimal avg = sum.divide(BigDecimal.valueOf(cnt), 2, RoundingMode.HALF_UP);
        if (HolidayUtil.isWeekend(day)) {
            avg = avg.add(new BigDecimal("5.00"));
        }
        if (avg.compareTo(new BigDecimal("100")) > 0) {
            avg = new BigDecimal("100.00");
        }
        return avg;
    }

    private PredictionResult latestDaily(LocalDate day) {
        List<PredictionResult> rows = predictionResultMapper.selectList(
                new LambdaQueryWrapper<PredictionResult>()
                        .eq(PredictionResult::getTargetType, "daily")
                        .eq(PredictionResult::getTarget, day.toString())
                        .eq(PredictionResult::getMetric, "occupancy_rate")
                        .orderByDesc(PredictionResult::getGeneratedAt)
                        .last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> toResultItem(PredictionResult pr) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", pr.getId());
        m.put("target", pr.getTarget());
        m.put("metric", pr.getMetric());
        m.put("predictedValue", pr.getPredictedValue());
        m.put("engine", pr.getEngine());
        m.put("modelVersion", pr.getModelVersion());
        m.put("llmInterpretation", pr.getLlmInterpretation());
        m.put("confidenceLow", pr.getConfidenceLow());
        m.put("confidenceHigh", pr.getConfidenceHigh());
        m.put("generatedAt", pr.getGeneratedAt() == null ? null
                : pr.getGeneratedAt().toString().replace('T', ' '));
        return m;
    }

    private static BigDecimal dec(Object o) {
        if (o == null) {
            throw BizException.badRequest("旁车预测结果缺少 predictedValue");
        }
        if (o instanceof BigDecimal bd) {
            return bd;
        }
        if (o instanceof Number num) {
            return new BigDecimal(num.toString());
        }
        try {
            return new BigDecimal(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            throw BizException.badRequest("旁车预测结果 predictedValue 非法");
        }
    }

    private static BigDecimal decOrNull(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof BigDecimal bd) {
            return bd;
        }
        if (o instanceof Number num) {
            try {
                return new BigDecimal(num.toString());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        try {
            return new BigDecimal(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T v : values) {
            if (v != null && !(v instanceof String s && s.isBlank())) {
                return v;
            }
        }
        return null;
    }
}
