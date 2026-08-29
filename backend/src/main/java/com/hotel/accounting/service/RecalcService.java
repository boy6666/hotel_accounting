package com.hotel.accounting.service;

import com.hotel.accounting.mapper.ChannelMapper;
import com.hotel.accounting.mapper.ChannelMonthlyMapper;
import com.hotel.accounting.mapper.DailyOccupancyMapper;
import com.hotel.accounting.mapper.DailyOccupiedRoomMapper;
import com.hotel.accounting.mapper.MonthlyCostMapper;
import com.hotel.accounting.mapper.MonthlySummaryMapper;
import com.hotel.accounting.mapper.RoomMapper;
import com.hotel.accounting.model.Channel;
import com.hotel.accounting.model.ChannelMonthly;
import com.hotel.accounting.model.DailyOccupancy;
import com.hotel.accounting.model.MonthlyCost;
import com.hotel.accounting.model.MonthlySummary;
import com.hotel.accounting.model.Room;
import com.hotel.accounting.util.Months;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 月度汇总重算服务（BE-06 核心，横切）。
 *
 * <p><b>幂等</b>：给定月份 → 由 {@code daily_occupancy}（含房态明细推导）/ {@code channel_monthly} /
 * {@code monthly_cost} 重算并 UPSERT 进 {@code monthly_summary}。成本/房态/渠道任何变动、房间启停变化、
 * 导入确认后都必须调用它。</p>
 *
 * <p>对账口径（02 §5.2）：{@code 房夜 = Σ daily_occupancy.occupied_rooms} vs
 * {@code 间夜 = Σ channel_monthly.nights}；相等 → matched，不等 → diff（记 diff 值 + 渠道维排查），
 * 两侧均无数据 → unchecked。</p>
 *
 * <p>注意：occupancy_rate 分母采用「当月有房态记录的 dayCount × 平均可售房间数」，
 * 与 seed（198/(22×10)=90.00）一致，而非自然月天数。</p>
 */
@Service
public class RecalcService {

    private static final Logger log = LoggerFactory.getLogger(RecalcService.class);

    private final DailyOccupancyMapper dailyOccupancyMapper;
    private final DailyOccupiedRoomMapper dailyOccupiedRoomMapper;
    private final RoomMapper roomMapper;
    private final ChannelMapper channelMapper;
    private final ChannelMonthlyMapper channelMonthlyMapper;
    private final MonthlyCostMapper monthlyCostMapper;
    private final MonthlySummaryMapper monthlySummaryMapper;

    public RecalcService(DailyOccupancyMapper dailyOccupancyMapper,
                         DailyOccupiedRoomMapper dailyOccupiedRoomMapper,
                         RoomMapper roomMapper,
                         ChannelMapper channelMapper,
                         ChannelMonthlyMapper channelMonthlyMapper,
                         MonthlyCostMapper monthlyCostMapper,
                         MonthlySummaryMapper monthlySummaryMapper) {
        this.dailyOccupancyMapper = dailyOccupancyMapper;
        this.dailyOccupiedRoomMapper = dailyOccupiedRoomMapper;
        this.roomMapper = roomMapper;
        this.channelMapper = channelMapper;
        this.channelMonthlyMapper = channelMonthlyMapper;
        this.monthlyCostMapper = monthlyCostMapper;
        this.monthlySummaryMapper = monthlySummaryMapper;
    }

    public int enabledRoomCount() {
        return (int) roomMapper.countEnabled();
    }

    /**
     * 重算并 UPSERT 月度汇总（含对账状态落点）。返回重算后的汇总行。
     */
    @Transactional
    public MonthlySummary recalc(String month) {
        YearMonth ym = Months.require(month);
        LocalDate from = Months.firstDay(ym);
        LocalDate to = Months.lastDay(ym);

        // 1) 刷新当月 daily_occupancy：total_rooms=当前可售快照；occupied_rooms=明细计数
        int enabledRooms = enabledRoomCount();
        Map<LocalDate, Integer> detailCount = dailyOccupiedRoomMapper.selectBetween(from, to).stream()
                .collect(Collectors.groupingBy(d -> d.getBizDate(), Collectors.summingInt(x -> 1)));

        List<DailyOccupancy> existing = dailyOccupancyMapper.selectList(new LambdaQueryWrapper<DailyOccupancy>()
                .between(DailyOccupancy::getBizDate, from, to));
        Set<LocalDate> allDays = new TreeSet<>();
        existing.forEach(r -> allDays.add(r.getBizDate()));
        allDays.addAll(detailCount.keySet());

        for (LocalDate day : allDays) {
            int occ = detailCount.getOrDefault(day, 0);
            DailyOccupancy row = existing.stream().filter(r -> r.getBizDate().equals(day)).findFirst().orElse(null);
            if (row == null) {
                DailyOccupancy n = new DailyOccupancy();
                n.setBizDate(day);
                n.setOccupiedRooms(occ);
                n.setTotalRooms(enabledRooms);
                n.setSource("manual");
                dailyOccupancyMapper.insert(n);
            } else {
                row.setOccupiedRooms(occ);
                row.setTotalRooms(enabledRooms);
                dailyOccupancyMapper.updateById(row);
            }
        }

        // 2) 房夜合计（对账左口径）
        List<DailyOccupancy> days = dailyOccupancyMapper.selectList(new LambdaQueryWrapper<DailyOccupancy>()
                .between(DailyOccupancy::getBizDate, from, to));
        int occupancyNights = days.stream().mapToInt(DailyOccupancy::getOccupiedRooms).sum();
        int dayCount = days.size();
        double avgTotalRooms = days.stream().mapToInt(DailyOccupancy::getTotalRooms).average().orElse(0);

        // 3) 渠道×月 → 收入/间夜（线上/线下拆分）；按渠道当前佣金率重算挂牌与佣金，保持冗余一致
        List<Map<String, Object>> channelCalc = new ArrayList<>();
        List<ChannelMonthly> chmRows = channelMonthlyMapper.selectList(
                new LambdaQueryWrapper<ChannelMonthly>().eq(ChannelMonthly::getMonth, month));
        Map<Long, Channel> channelById = channelMapper.selectList(null).stream()
                .collect(Collectors.toMap(Channel::getId, c -> c, (a, b) -> a));

        BigDecimal revenue = BigDecimal.ZERO;
        BigDecimal grossRevenue = BigDecimal.ZERO;
        BigDecimal commission = BigDecimal.ZERO;
        int nightsSum = 0;
        int onlineNights = 0;
        int offlineNights = 0;

        for (ChannelMonthly cm : chmRows) {
            Channel ch = channelById.get(cm.getChannelId());
            String type = ch == null ? "offline" : ch.getType();
            BigDecimal rate = (ch == null || ch.getCommissionRate() == null)
                    ? BigDecimal.ZERO : ch.getCommissionRate();

            BigDecimal rev = nz(cm.getRevenue());
            BigDecimal gross;
            BigDecimal comm;
            if ("online".equals(type) && rate.compareTo(BigDecimal.ZERO) > 0 && rate.compareTo(BigDecimal.ONE) < 0) {
                BigDecimal denom = BigDecimal.ONE.subtract(rate);
                gross = rev.divide(denom, 2, RoundingMode.HALF_UP);
                comm = gross.subtract(rev);
            } else {
                gross = rev;
                comm = BigDecimal.ZERO;
            }
            // 回写冗余（与新佣金率一致）
            cm.setGrossRevenue(gross);
            cm.setCommission(comm);
            channelMonthlyMapper.updateById(cm);

            revenue = revenue.add(rev);
            grossRevenue = grossRevenue.add(gross);
            commission = commission.add(comm);
            nightsSum += cm.getNights() == null ? 0 : cm.getNights();
            if ("online".equals(type)) {
                onlineNights += cm.getNights() == null ? 0 : cm.getNights();
            } else {
                offlineNights += cm.getNights() == null ? 0 : cm.getNights();
            }

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("channel", ch);
            m.put("nights", cm.getNights() == null ? 0 : cm.getNights());
            channelCalc.add(m);
        }

        // 4) 成本合计
        BigDecimal totalCost = monthlyCostMapper.selectList(
                        new LambdaQueryWrapper<MonthlyCost>().eq(MonthlyCost::getMonth, month)).stream()
                .map(MonthlyCost::getAmount)
                .filter(a -> a != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 5) 组装汇总
        BigDecimal profit = revenue.subtract(totalCost);
        BigDecimal adr = nightsSum == 0 ? BigDecimal.ZERO
                : revenue.divide(BigDecimal.valueOf(nightsSum), 2, RoundingMode.HALF_UP);
        BigDecimal occupancyRate = null;
        if (dayCount > 0 && enabledRooms > 0) {
            double rate = occupancyNights * 100.0 / (dayCount * avgTotalRooms);
            occupancyRate = BigDecimal.valueOf(rate).setScale(2, RoundingMode.HALF_UP);
        }

        MonthlySummary summary = monthlySummaryMapper.selectOne(
                new LambdaQueryWrapper<MonthlySummary>().eq(MonthlySummary::getMonth, month));
        boolean isNew = summary == null;
        if (isNew) {
            summary = new MonthlySummary();
            summary.setMonth(month);
        }
        summary.setRevenue(revenue);
        summary.setGrossRevenue(grossRevenue);
        summary.setCommission(commission);
        summary.setNights(nightsSum);
        summary.setAdr(adr);
        summary.setOnlineNights(onlineNights);
        summary.setOfflineNights(offlineNights);
        summary.setOccupancyRate(occupancyRate);
        summary.setTotalCost(totalCost);
        summary.setProfit(profit);
        summary.setDataStatus("computed");
        ReconcileInfo ri = reconcile(month);
        summary.setReconcileStatus(ri.getStatus());
        summary.setNote(ri.getStatus().equals("matched")
                ? "房态" + ri.getOccupancyNights() + " vs 流水" + ri.getChannelNights() + "，对账通过"
                : null);

        if (isNew) {
            monthlySummaryMapper.insert(summary);
        } else {
            monthlySummaryMapper.updateById(summary);
        }
        log.info("月度重算 month={} revenue={} cost={} profit={} nights={} occ={} reconcile={}",
                month, revenue, totalCost, profit, nightsSum, occupancyRate, ri.getStatus());
        return summary;
    }

    /**
     * 对账（只读，按当前库内数据）。drive 供看板/房态详页/导入确认复用。
     */
    public ReconcileInfo reconcile(String month) {
        YearMonth ym = Months.require(month);
        LocalDate from = Months.firstDay(ym);
        LocalDate to = Months.lastDay(ym);

        int occupancyNights = dailyOccupancyMapper.selectList(
                        new LambdaQueryWrapper<DailyOccupancy>().between(DailyOccupancy::getBizDate, from, to))
                .stream().mapToInt(DailyOccupancy::getOccupiedRooms).sum();

        List<ChannelMonthly> chmRows = channelMonthlyMapper.selectList(
                new LambdaQueryWrapper<ChannelMonthly>().eq(ChannelMonthly::getMonth, month));
        int channelNights = chmRows.stream().mapToInt(c -> c.getNights() == null ? 0 : c.getNights()).sum();

        int diff = occupancyNights - channelNights;
        String status;
        if (occupancyNights == 0 && channelNights == 0) {
            status = "unchecked";
        } else if (diff == 0) {
            status = "matched";
        } else {
            status = "diff";
        }

        List<ReconcileInfo.ChannelDiff> details = new ArrayList<>();
        if (diff != 0) {
            if (channelNights == 0) {
                Map<String, Integer> onlyRoom = new LinkedHashMap<>();
                onlyRoom.put("（全部渠道）", occupancyNights);
            } else {
                Map<Long, String> names = channelMapper.selectList(null).stream()
                        .collect(Collectors.toMap(Channel::getId, Channel::getName, (a, b) -> a));
                for (ChannelMonthly cm : chmRows) {
                    int n = cm.getNights() == null ? 0 : cm.getNights();
                    double share = (double) n / channelNights;
                    long actual = Math.round(occupancyNights * share);
                    ReconcileInfo.ChannelDiff d = new ReconcileInfo.ChannelDiff();
                    d.setChannelName(names.getOrDefault(cm.getChannelId(), "渠道#" + cm.getChannelId()));
                    d.setReportedNights(n);
                    d.setActualRoomNights((int) actual);
                    d.setDiff((int) actual - n);
                    details.add(d);
                }
            }
        }

        ReconcileInfo info = new ReconcileInfo();
        info.setMonth(month);
        info.setOccupancyNights(occupancyNights);
        info.setChannelNights(channelNights);
        info.setDiff(diff);
        info.setStatus(status);
        info.setDetailChannels(details);
        return info;
    }

    /**
     * 重算所有出现过流水的月份（含汇总/房态/渠道/成本），用于房间启停、佣金率变更、导入确认等全局影响。
     */
    public List<MonthlySummary> recalcAllMonths() {
        Set<String> months = new HashSet<>();
        dailyOccupancyMapper.selectList(null).stream()
                .map(r -> r.getBizDate().toString().substring(0, 7)).forEach(months::add);
        monthlyCostMapper.selectList(null).stream()
                .map(MonthlyCost::getMonth).forEach(months::add);
        channelMonthlyMapper.selectList(null).stream()
                .map(ChannelMonthly::getMonth).forEach(months::add);
        monthlySummaryMapper.selectList(null).stream()
                .map(MonthlySummary::getMonth).forEach(months::add);
        List<MonthlySummary> out = new ArrayList<>();
        for (String m : months) {
            try {
                out.add(recalc(m));
            } catch (Exception e) {
                log.error("重算失败 month={}", m, e);
            }
        }
        return out;
    }

    /** 按 id 查房间（用于自动建档等）。 */
    public Room getRoom(Long id) {
        return roomMapper.selectById(id);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
