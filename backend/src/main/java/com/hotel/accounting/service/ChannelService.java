package com.hotel.accounting.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hotel.accounting.common.BizException;
import com.hotel.accounting.mapper.ChannelMapper;
import com.hotel.accounting.mapper.ChannelMonthlyMapper;
import com.hotel.accounting.model.Channel;
import com.hotel.accounting.model.ChannelMonthly;
import com.hotel.accounting.util.AuditLogger;
import com.hotel.accounting.util.Months;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 渠道字典 + 渠道×月统计（BE-05）。渠道只在设置页改佣金/停用/新增；渠道流水手录/修正走 /api/channel-monthly。
 */
@Service
public class ChannelService {

    private final ChannelMapper channelMapper;
    private final ChannelMonthlyMapper channelMonthlyMapper;
    private final RecalcService recalcService;
    private final AuditLogger audit;

    public ChannelService(ChannelMapper channelMapper, ChannelMonthlyMapper channelMonthlyMapper,
                          RecalcService recalcService, AuditLogger audit) {
        this.channelMapper = channelMapper;
        this.channelMonthlyMapper = channelMonthlyMapper;
        this.recalcService = recalcService;
        this.audit = audit;
    }

    public List<Channel> listChannels(String type, Integer enabled) {
        LambdaQueryWrapper<Channel> qw = new LambdaQueryWrapper<Channel>()
                .orderByAsc(Channel::getSortOrder).orderByAsc(Channel::getId);
        if (type != null && !type.isBlank()) {
            qw.eq(Channel::getType, type);
        }
        if (enabled != null) {
            qw.eq(Channel::getEnabled, enabled);
        }
        return channelMapper.selectList(qw);
    }

    @Transactional
    public Channel createChannel(ChannelReq req) {
        if (req.getName() == null || req.getName().isBlank()) {
            throw BizException.badRequest("渠道名不能为空");
        }
        String name = req.getName().trim();
        boolean dup = channelMapper.selectList(null).stream()
                .anyMatch(c -> name.equals(c.getName()));
        if (dup) {
            throw BizException.conflict("渠道已存在: " + name);
        }
        String type = req.getType() == null || req.getType().isBlank() ? "online" : req.getType().trim();
        if (!"online".equals(type) && !"offline".equals(type)) {
            throw BizException.badRequest("type 必须为 online / offline");
        }
        Channel ch = new Channel();
        ch.setName(name);
        ch.setType(type);
        ch.setCommissionRate(req.getCommissionRate() == null ? BigDecimal.ZERO : req.getCommissionRate());
        validateRate(ch.getCommissionRate());
        Integer maxSort = channelMapper.selectList(null).stream()
                .mapToInt(c -> c.getSortOrder() == null ? 0 : c.getSortOrder()).max().orElse(0);
        ch.setSortOrder(req.getSortOrder() == null ? maxSort + 1 : req.getSortOrder());
        ch.setEnabled(1);
        channelMapper.insert(ch);
        audit.log("CREATE_CHANNEL", "name=" + name + " type=" + type + " rate=" + ch.getCommissionRate());
        return ch;
    }

    @Transactional
    public Channel updateChannel(Long id, ChannelReq req) {
        Channel ch = requireChannel(id);
        boolean rateOrTypeChanged = false;
        if (req.getType() != null && !req.getType().isBlank()) {
            if (!"online".equals(req.getType()) && !"offline".equals(req.getType())) {
                throw BizException.badRequest("type 必须为 online / offline");
            }
            if (!req.getType().equals(ch.getType())) {
                ch.setType(req.getType());
                rateOrTypeChanged = true;
            }
        }
        if (req.getCommissionRate() != null) {
            validateRate(req.getCommissionRate());
            if (req.getCommissionRate().compareTo(ch.getCommissionRate()) != 0) {
                ch.setCommissionRate(req.getCommissionRate());
                rateOrTypeChanged = true;
            }
        }
        if (req.getEnabled() != null) {
            ch.setEnabled(req.getEnabled() ? 1 : 0);
        }
        if (req.getSortOrder() != null) {
            ch.setSortOrder(req.getSortOrder());
        }
        channelMapper.updateById(ch);
        audit.log("UPDATE_CHANNEL", "channel#" + id + " name=" + ch.getName() + " rate=" + ch.getCommissionRate());
        if (rateOrTypeChanged) {
            // 佣金率/类型变化影响当月及历史挂牌与佣金冗余 → 重算相关月份
            List<String> months = channelMonthlyMapper.selectList(
                            new LambdaQueryWrapper<ChannelMonthly>().eq(ChannelMonthly::getChannelId, id))
                    .stream().map(ChannelMonthly::getMonth).distinct().collect(Collectors.toList());
            months.forEach(recalcService::recalc);
        }
        return ch;
    }

    /**
     * 渠道×月统计（7.4）：join 渠道字典，口径到手价；gross/commission 按渠道当前率重算。
     */
    public Map<String, Object> channelMonthlyList(String month) {
        Months.require(month);
        List<ChannelMonthly> rows = channelMonthlyMapper.selectList(
                new LambdaQueryWrapper<ChannelMonthly>().eq(ChannelMonthly::getMonth, month));
        Map<Long, Channel> channels = channelMapper.selectList(null).stream()
                .collect(Collectors.toMap(Channel::getId, c -> c, (a, b) -> a));
        int totalNights = rows.stream().mapToInt(r -> r.getNights() == null ? 0 : r.getNights()).sum();

        List<Map<String, Object>> list = new ArrayList<>();
        for (ChannelMonthly cm : rows) {
            Channel ch = channels.get(cm.getChannelId());
            String type = ch == null ? "offline" : ch.getType();
            BigDecimal rate = (ch == null || ch.getCommissionRate() == null) ? BigDecimal.ZERO : ch.getCommissionRate();
            BigDecimal rev = nz(cm.getRevenue());
            int nights = cm.getNights() == null ? 0 : cm.getNights();
            BigDecimal gross;
            BigDecimal comm;
            if ("online".equals(type) && rate.compareTo(BigDecimal.ZERO) > 0 && rate.compareTo(BigDecimal.ONE) < 0) {
                gross = rev.divide(BigDecimal.ONE.subtract(rate), 2, RoundingMode.HALF_UP);
                comm = gross.subtract(rev);
            } else {
                gross = rev;
                comm = BigDecimal.ZERO;
            }
            BigDecimal avg = nights == 0 ? BigDecimal.ZERO
                    : rev.divide(BigDecimal.valueOf(nights), 2, RoundingMode.HALF_UP);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("channelId", cm.getChannelId());
            m.put("channelName", ch == null ? "渠道#" + cm.getChannelId() : ch.getName());
            m.put("type", type);
            m.put("nights", nights);
            m.put("revenue", rev);
            m.put("grossRevenue", gross);
            m.put("commission", comm);
            m.put("commissionRate", rate);
            m.put("avgPrice", avg);
            m.put("share", totalNights == 0 ? null
                    : BigDecimal.valueOf(nights).divide(BigDecimal.valueOf(totalNights), 4, RoundingMode.HALF_UP));
            list.add(m);
        }
        list.sort((a, b) -> Integer.compare((Integer) b.get("nights"), (Integer) a.get("nights")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("month", month);
        out.put("list", list);
        return out;
    }

    /**
     * 手录/修正某渠道本月数（POST 幂等 upsert；/(month,channel_id) 唯一）。扩展接口，供成本/渠道页入口。
     */
    @Transactional
    public Map<String, Object> upsertChannelMonthly(String month, ChannelMonthlyReq req) {
        Months.require(month);
        if (req.getChannelId() == null) {
            throw BizException.badRequest("channelId 不能为空");
        }
        Channel ch = requireChannel(req.getChannelId());
        ChannelMonthly cm = channelMonthlyMapper.selectOne(new LambdaQueryWrapper<ChannelMonthly>()
                .eq(ChannelMonthly::getMonth, month).eq(ChannelMonthly::getChannelId, req.getChannelId()));
        boolean isNew = cm == null;
        if (isNew) {
            cm = new ChannelMonthly();
            cm.setMonth(month);
            cm.setChannelId(ch.getId());
        }
        cm.setNights(req.getNights() == null ? 0 : req.getNights());
        cm.setRevenue(req.getRevenue() == null ? BigDecimal.ZERO : req.getRevenue());
        if (req.getNote() != null) {
            cm.setNote(req.getNote());
        }
        computeDerived(cm, ch);
        if (isNew) {
            channelMonthlyMapper.insert(cm);
        } else {
            channelMonthlyMapper.updateById(cm);
        }
        audit.logAmount("UPSERT_CHANNEL_MONTHLY", "month=" + month + " channel=" + ch.getName(),
                "nights=" + cm.getNights() + " revenue=" + cm.getRevenue());
        recalcService.recalc(month);
        return Map.of("id", cm.getId(), "month", month,
                "channelId", ch.getId(), "channelName", ch.getName(),
                "nights", cm.getNights(), "revenue", cm.getRevenue(),
                "grossRevenue", cm.getGrossRevenue(), "commission", cm.getCommission(),
                "avgPrice", cm.getAvgPrice());
    }

    @Transactional
    public Map<String, Object> updateChannelMonthly(Long id, ChannelMonthlyReq req) {
        ChannelMonthly cm = channelMonthlyMapper.selectById(id);
        if (cm == null) {
            throw BizException.notFound("渠道月度记录不存在: id=" + id);
        }
        if (req.getNights() != null) {
            cm.setNights(req.getNights());
        }
        if (req.getRevenue() != null) {
            cm.setRevenue(req.getRevenue());
        }
        if (req.getNote() != null) {
            cm.setNote(req.getNote());
        }
        Channel ch = requireChannel(cm.getChannelId());
        computeDerived(cm, ch);
        channelMonthlyMapper.updateById(cm);
        audit.logAmount("UPDATE_CHANNEL_MONTHLY", "id=" + id + " month=" + cm.getMonth(),
                "nights=" + cm.getNights() + " revenue=" + cm.getRevenue());
        recalcService.recalc(cm.getMonth());
        return channelMonthlyList(cm.getMonth());
    }

    /** 渠道间夜/收入趋势（7.5）：按月聚合。 */
    public List<Map<String, Object>> channelTrend(String from, String to) {
        YearMonth fromYm = Months.require(from);
        YearMonth toYm = Months.require(to);
        if (fromYm.isAfter(toYm)) {
            throw BizException.badRequest("from 不能晚于 to");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (YearMonth ym = fromYm; !ym.isAfter(toYm); ym = ym.plusMonths(1)) {
            String m = Months.format(ym);
            List<ChannelMonthly> rows = channelMonthlyMapper.selectList(
                    new LambdaQueryWrapper<ChannelMonthly>().eq(ChannelMonthly::getMonth, m));
            int nights = rows.stream().mapToInt(r -> r.getNights() == null ? 0 : r.getNights()).sum();
            BigDecimal revenue = rows.stream().map(r -> nz(r.getRevenue()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("month", m);
            e.put("nights", nights);
            e.put("revenue", revenue);
            out.add(e);
        }
        return out;
    }

    public Channel requireChannel(Long id) {
        Channel ch = channelMapper.selectById(id);
        if (ch == null) {
            throw BizException.notFound("渠道不存在: id=" + id);
        }
        return ch;
    }

    /** 按名查找渠道（含停用）；不存在返回 null。 */
    public Channel findByExactName(String name) {
        if (name == null) {
            return null;
        }
        return channelMapper.selectList(null).stream()
                .filter(c -> name.strip().equals(c.getName()))
                .findFirst().orElse(null);
    }

    private void computeDerived(ChannelMonthly cm, Channel ch) {
        BigDecimal rev = nz(cm.getRevenue());
        int nights = cm.getNights() == null ? 0 : cm.getNights();
        BigDecimal gross;
        BigDecimal comm;
        if ("online".equals(ch.getType()) && ch.getCommissionRate() != null
                && ch.getCommissionRate().compareTo(BigDecimal.ZERO) > 0) {
            gross = rev.divide(BigDecimal.ONE.subtract(ch.getCommissionRate()), 2, RoundingMode.HALF_UP);
            comm = gross.subtract(rev);
        } else {
            gross = rev;
            comm = BigDecimal.ZERO;
        }
        cm.setGrossRevenue(gross);
        cm.setCommission(comm);
        cm.setAvgPrice(nights == 0 ? BigDecimal.ZERO
                : rev.divide(BigDecimal.valueOf(nights), 2, RoundingMode.HALF_UP));
    }

    private static void validateRate(BigDecimal rate) {
        if (rate == null || rate.compareTo(BigDecimal.ZERO) < 0 || rate.compareTo(BigDecimal.ONE) >= 0) {
            throw BizException.badRequest("commissionRate 必须满足 0 ≤ rate < 1");
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    public static class ChannelReq {
        private String name;
        private String type;
        private BigDecimal commissionRate;
        private Integer sortOrder;
        private Boolean enabled;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public BigDecimal getCommissionRate() {
            return commissionRate;
        }

        public void setCommissionRate(BigDecimal commissionRate) {
            this.commissionRate = commissionRate;
        }

        public Integer getSortOrder() {
            return sortOrder;
        }

        public void setSortOrder(Integer sortOrder) {
            this.sortOrder = sortOrder;
        }

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class ChannelMonthlyReq {
        private Long channelId;
        private Integer nights;
        private BigDecimal revenue;
        private String note;

        public Long getChannelId() {
            return channelId;
        }

        public void setChannelId(Long channelId) {
            this.channelId = channelId;
        }

        public Integer getNights() {
            return nights;
        }

        public void setNights(Integer nights) {
            this.nights = nights;
        }

        public BigDecimal getRevenue() {
            return revenue;
        }

        public void setRevenue(BigDecimal revenue) {
            this.revenue = revenue;
        }

        public String getNote() {
            return note;
        }

        public void setNote(String note) {
            this.note = note;
        }
    }
}
