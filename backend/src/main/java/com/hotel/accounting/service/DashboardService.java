package com.hotel.accounting.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hotel.accounting.mapper.ChannelMapper;
import com.hotel.accounting.mapper.ChannelMonthlyMapper;
import com.hotel.accounting.mapper.MonthlyCostMapper;
import com.hotel.accounting.mapper.MonthlySummaryMapper;
import com.hotel.accounting.model.Channel;
import com.hotel.accounting.model.ChannelMonthly;
import com.hotel.accounting.model.MonthlyCost;
import com.hotel.accounting.model.MonthlySummary;
import com.hotel.accounting.util.Months;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 首页看板（BE-07，03 §5）：月度总览卡（含环比 delta）+ 趋势 + 成本结构 + 渠道占比 + 对账摘要。
 * 全部只读，数据源为 {@code monthly_summary} 冗余 + 明细表。
 */
@Service
public class DashboardService {

    private final MonthlySummaryMapper summaryMapper;
    private final MonthlyCostMapper monthlyCostMapper;
    private final ChannelMonthlyMapper channelMonthlyMapper;
    private final ChannelMapper channelMapper;
    private final RecalcService recalcService;

    public DashboardService(MonthlySummaryMapper summaryMapper,
                            MonthlyCostMapper monthlyCostMapper,
                            ChannelMonthlyMapper channelMonthlyMapper,
                            ChannelMapper channelMapper,
                            RecalcService recalcService) {
        this.summaryMapper = summaryMapper;
        this.monthlyCostMapper = monthlyCostMapper;
        this.channelMonthlyMapper = channelMonthlyMapper;
        this.channelMapper = channelMapper;
        this.recalcService = recalcService;
    }

    /** 5.1 总览卡 + 环比 delta。 */
    public Map<String, Object> overview(String month) {
        YearMonth ym = Months.require(month);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("month", month);
        MonthlySummary cur = byMonth(month);
        MonthlySummary prev = byMonth(Months.format(ym.minusMonths(1)));
        if (cur == null) {
            m.put("empty", true);
            return m;
        }
        m.put("revenue", nz(cur.getRevenue()));
        m.put("totalCost", nz(cur.getTotalCost()));
        m.put("profit", nz(cur.getProfit()));
        m.put("nights", cur.getNights() == null ? 0 : cur.getNights());
        m.put("adr", nz(cur.getAdr()));
        m.put("occupancyRate", cur.getOccupancyRate() == null ? null : cur.getOccupancyRate());
        if (prev != null) {
            m.put("revenueDelta", delta(cur.getRevenue(), prev.getRevenue()));
            m.put("totalCostDelta", delta(cur.getTotalCost(), prev.getTotalCost()));
            m.put("profitDelta", delta(cur.getProfit(), prev.getProfit()));
            m.put("nightsDelta", cur.getNights() == null || prev.getNights() == null
                    ? null : delta(cur.getNights(), prev.getNights()));
            m.put("adrDelta", delta(cur.getAdr(), prev.getAdr()));
            m.put("occupancyRateDelta", delta(cur.getOccupancyRate(), prev.getOccupancyRate()));
        }
        return m;
    }

    /** 5.2 月度收入/成本/利润折线（区间逐月；无汇总月补 0）。 */
    public Map<String, Object> trend(String from, String to) {
        YearMonth fromYm = Months.require(from);
        YearMonth toYm = Months.require(to);
        if (fromYm.isAfter(toYm)) {
            throw com.hotel.accounting.common.BizException.badRequest("from 不能晚于 to");
        }
        List<String> months = new ArrayList<>();
        List<BigDecimal> revenue = new ArrayList<>();
        List<BigDecimal> cost = new ArrayList<>();
        List<BigDecimal> profit = new ArrayList<>();
        for (YearMonth ym = fromYm; !ym.isAfter(toYm); ym = ym.plusMonths(1)) {
            String m = Months.format(ym);
            MonthlySummary s = byMonth(m);
            months.add(m);
            revenue.add(s == null ? BigDecimal.ZERO : nz(s.getRevenue()));
            cost.add(s == null ? BigDecimal.ZERO : nz(s.getTotalCost()));
            profit.add(s == null ? BigDecimal.ZERO : nz(s.getProfit()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("months", months);
        out.put("revenue", revenue);
        out.put("cost", cost);
        out.put("profit", profit);
        return out;
    }

    /** 5.3 成本结构：三类小计 + 合计 + TOP5 费用项。 */
    public Map<String, Object> costStructure(String month) {
        Months.require(month);
        List<MonthlyCost> rows = monthlyCostMapper.selectList(
                new LambdaQueryWrapper<MonthlyCost>().eq(MonthlyCost::getMonth, month))
                .stream().sorted(Comparator.comparing(MonthlyCost::getAmount,
                        Comparator.nullsLast(Comparator.reverseOrder()))).collect(Collectors.toList());
        BigDecimal fixed = sum(rows, "fixed");
        BigDecimal variable = sum(rows, "variable");
        BigDecimal oneTime = sum(rows, "one_time");
        List<Map<String, Object>> topItems = rows.stream().limit(5).map(r -> {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("name", r.getItemName());
            e.put("amount", nz(r.getAmount()));
            e.put("type", r.getType());
            return e;
        }).collect(Collectors.toList());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("month", month);
        out.put("fixed", fixed);
        out.put("variable", variable);
        out.put("one_time", oneTime);
        out.put("total", fixed.add(variable).add(oneTime));
        out.put("topItems", topItems);
        return out;
    }

    /** 5.4 渠道占比：线上/线下收入与间夜 + 渠道 TOP。 */
    public Map<String, Object> channelRatio(String month) {
        Months.require(month);
        List<ChannelMonthly> rows = channelMonthlyMapper.selectList(
                new LambdaQueryWrapper<ChannelMonthly>().eq(ChannelMonthly::getMonth, month));
        Map<Long, Channel> channels = channelMapper.selectList(null).stream()
                .collect(Collectors.toMap(Channel::getId, c -> c, (a, b) -> a));
        BigDecimal onlineRevenue = BigDecimal.ZERO;
        BigDecimal offlineRevenue = BigDecimal.ZERO;
        int onlineNights = 0;
        int offlineNights = 0;
        for (ChannelMonthly cm : rows) {
            Channel ch = channels.get(cm.getChannelId());
            boolean online = ch != null && "online".equals(ch.getType());
            BigDecimal rev = nz(cm.getRevenue());
            int nights = cm.getNights() == null ? 0 : cm.getNights();
            if (online) {
                onlineRevenue = onlineRevenue.add(rev);
                onlineNights += nights;
            } else {
                offlineRevenue = offlineRevenue.add(rev);
                offlineNights += nights;
            }
        }
        List<Map<String, Object>> top = rows.stream()
                .sorted(Comparator.comparing(ChannelMonthly::getNights,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(6)
                .map(cm -> {
                    Channel ch = channels.get(cm.getChannelId());
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("channel", ch == null ? "渠道#" + cm.getChannelId() : ch.getName());
                    e.put("nights", cm.getNights() == null ? 0 : cm.getNights());
                    e.put("revenue", nz(cm.getRevenue()));
                    return e;
                }).collect(Collectors.toList());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("month", month);
        out.put("onlineRevenue", onlineRevenue);
        out.put("offlineRevenue", offlineRevenue);
        out.put("onlineNights", onlineNights);
        out.put("offlineNights", offlineNights);
        out.put("top", top);
        return out;
    }

    /** 5.5 对账摘要（房态间夜 vs 流水间夜、diff）。 */
    public ReconcileInfo reconcile(String month) {
        return recalcService.reconcile(month);
    }

    private MonthlySummary byMonth(String month) {
        return summaryMapper.selectOne(
                new LambdaQueryWrapper<MonthlySummary>().eq(MonthlySummary::getMonth, month));
    }

    private static BigDecimal sum(List<MonthlyCost> rows, String type) {
        return rows.stream().filter(r -> type.equals(r.getType()))
                .map(r -> nz(r.getAmount())).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static Double delta(BigDecimal cur, BigDecimal prev) {
        if (cur == null || prev == null || prev.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return cur.subtract(prev).divide(prev, 6, RoundingMode.HALF_UP).doubleValue();
    }

    private static Double delta(Integer cur, Integer prev) {
        if (cur == null || prev == null || prev == 0) {
            return null;
        }
        return (double) (cur - prev) / prev;
    }
}
