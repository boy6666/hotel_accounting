package com.hotel.accounting.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hotel.accounting.common.BizException;
import com.hotel.accounting.mapper.MonthlySummaryMapper;
import com.hotel.accounting.model.MonthlySummary;
import com.hotel.accounting.util.Months;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 利润分析（BE-06 展示侧，03 §8）：逐月利润表（含同比）+ 单月利润表头。只读。
 */
@Service
public class ProfitService {

    private final MonthlySummaryMapper summaryMapper;

    public ProfitService(MonthlySummaryMapper summaryMapper) {
        this.summaryMapper = summaryMapper;
    }

    /** 8.1 逐月利润表（含同比 yoy；无数据月补 0）。 */
    public Map<String, Object> monthly(String from, String to) {
        YearMonth fromYm = Months.require(from);
        YearMonth toYm = Months.require(to);
        if (fromYm.isAfter(toYm)) {
            throw BizException.badRequest("from 不能晚于 to");
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (YearMonth ym = fromYm; !ym.isAfter(toYm); ym = ym.plusMonths(1)) {
            String m = Months.format(ym);
            MonthlySummary s = byMonth(m);
            boolean hasData = s != null;
            BigDecimal revenue = hasData ? nz(s.getRevenue()) : BigDecimal.ZERO;
            BigDecimal totalCost = hasData ? nz(s.getTotalCost()) : BigDecimal.ZERO;
            BigDecimal profit = hasData ? nz(s.getProfit()) : BigDecimal.ZERO;
            int nights = hasData && s.getNights() != null ? s.getNights() : 0;
            BigDecimal adr = hasData && s.getAdr() != null ? s.getAdr() : BigDecimal.ZERO;
            BigDecimal perNightProfit = nights == 0 ? BigDecimal.ZERO
                    : profit.divide(BigDecimal.valueOf(nights), 2, RoundingMode.HALF_UP);

            MonthlySummary lastYear = byMonth(Months.format(ym.minusYears(1)));
            Map<String, Object> yoy = new LinkedHashMap<>();
            yoy.put("profit", maybeDelta(profit, lastYear == null ? null : lastYear.getProfit()));
            yoy.put("revenue", maybeDelta(revenue, lastYear == null ? null : lastYear.getRevenue()));
            yoy.put("nights", lastYear == null || lastYear.getNights() == null
                    ? null : maybeDelta(nights, lastYear.getNights()));

            Map<String, Object> e = new LinkedHashMap<>();
            e.put("month", m);
            e.put("revenue", revenue);
            e.put("totalCost", totalCost);
            e.put("profit", profit);
            e.put("nights", nights);
            e.put("adr", adr);
            e.put("perNightProfit", perNightProfit);
            e.put("hasData", hasData);
            e.put("yoy", yoy);
            list.add(e);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list", list);
        return out;
    }

    /** 8.2 单月利润表头。 */
    public Map<String, Object> summary(String month) {
        YearMonth ym = Months.require(month);
        MonthlySummary s = byMonth(month);
        BigDecimal revenue = s == null ? BigDecimal.ZERO : nz(s.getRevenue());
        BigDecimal totalCost = s == null ? BigDecimal.ZERO : nz(s.getTotalCost());
        BigDecimal profit = s == null ? BigDecimal.ZERO : nz(s.getProfit());
        int nights = s != null && s.getNights() != null ? s.getNights() : 0;
        BigDecimal adr = s == null ? BigDecimal.ZERO : nz(s.getAdr());
        BigDecimal perNightProfit = nights == 0 ? BigDecimal.ZERO
                : profit.divide(BigDecimal.valueOf(nights), 2, RoundingMode.HALF_UP);
        BigDecimal profitRate = revenue.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
                : profit.multiply(BigDecimal.valueOf(100)).divide(revenue, 2, RoundingMode.HALF_UP);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("month", Months.format(ym));
        out.put("revenue", revenue);
        out.put("grossRevenue", s == null ? null : s.getGrossRevenue());
        out.put("commission", s == null ? null : s.getCommission());
        out.put("totalCost", totalCost);
        out.put("profit", profit);
        out.put("nights", nights);
        out.put("adr", adr);
        out.put("perNightProfit", perNightProfit);
        out.put("profitRate", profitRate);
        out.put("occupancyRate", s == null ? null : s.getOccupancyRate());
        out.put("reconcileStatus", s == null ? "none" : s.getReconcileStatus());
        return out;
    }

    private MonthlySummary byMonth(String month) {
        return summaryMapper.selectOne(
                new LambdaQueryWrapper<MonthlySummary>().eq(MonthlySummary::getMonth, month));
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static Double maybeDelta(BigDecimal cur, BigDecimal prev) {
        if (cur == null || prev == null || prev.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return cur.subtract(prev).divide(prev, 6, RoundingMode.HALF_UP).doubleValue();
    }

    private static Double maybeDelta(int cur, int prev) {
        if (prev == 0) {
            return null;
        }
        return (double) (cur - prev) / prev;
    }
}
