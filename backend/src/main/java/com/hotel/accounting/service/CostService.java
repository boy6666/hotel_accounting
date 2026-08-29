package com.hotel.accounting.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hotel.accounting.common.BizException;
import com.hotel.accounting.common.PageResult;
import com.hotel.accounting.mapper.MonthlyCostMapper;
import com.hotel.accounting.model.CostItem;
import com.hotel.accounting.model.MonthlyCost;
import com.hotel.accounting.util.AuditLogger;
import com.hotel.accounting.util.Months;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 月度成本明细（BE-03，03 §6）。每次增删改后触发月度重算（BE-06）。
 * 手录 itemName 命中/近似成本字典 → 自动关联 costItemId 并存 import_mapping_rule（学习规则增强）。
 */
@Service
public class CostService {

    private static final Set<String> TYPES = Set.of("fixed", "variable", "one_time");

    private final MonthlyCostMapper monthlyCostMapper;
    private final CostItemService costItemService;
    private final MappingRuleService mappingRuleService;
    private final RecalcService recalcService;
    private final AuditLogger audit;

    public CostService(MonthlyCostMapper monthlyCostMapper,
                       CostItemService costItemService,
                       MappingRuleService mappingRuleService,
                       RecalcService recalcService,
                       AuditLogger audit) {
        this.monthlyCostMapper = monthlyCostMapper;
        this.costItemService = costItemService;
        this.mappingRuleService = mappingRuleService;
        this.recalcService = recalcService;
        this.audit = audit;
    }

    public PageResult<MonthlyCost> list(String month, String type, long page, long pageSize) {
        LambdaQueryWrapper<MonthlyCost> qw = new LambdaQueryWrapper<MonthlyCost>()
                .orderByDesc(MonthlyCost::getId);
        if (month != null && !month.isBlank()) {
            Months.require(month);
            qw.eq(MonthlyCost::getMonth, month);
        }
        if (type != null && !type.isBlank()) {
            if (!TYPES.contains(type)) {
                throw BizException.badRequest("type 必须是 fixed/variable/one_time");
            }
            qw.eq(MonthlyCost::getType, type);
        }
        List<MonthlyCost> all = monthlyCostMapper.selectList(qw);
        long total = all.size();
        int fromIdx = (int) Math.min(total, (page - 1) * pageSize);
        int toIdx = (int) Math.min(total, fromIdx + pageSize);
        return PageResult.of(all.subList(fromIdx, toIdx), total, page, pageSize);
    }

    @Transactional
    public MonthlyCost create(CostReq req) {
        Months.require(req.getMonth());
        if (req.getItemName() == null || req.getItemName().isBlank()) {
            throw BizException.badRequest("itemName 不能为空");
        }
        if (req.getAmount() == null || req.getAmount().compareTo(BigDecimal.ZERO) < 0) {
            throw BizException.badRequest("amount 必须 >= 0");
        }
        String name = req.getItemName().trim();
        CostItem matched = costItemService.findByExactName(name);
        Long costItemId = matched == null ? null : matched.getId();
        String type = req.getType();
        if (type == null || type.isBlank()) {
            if (matched != null) {
                type = matched.getDefaultType();
            } else {
                type = "variable";
            }
        }
        if (!TYPES.contains(type)) {
            throw BizException.badRequest("type 必须是 fixed/variable/one_time");
        }
        if (matched != null) {
            // 命中字典：增强学习规则
            mappingRuleService.record(name, matched.getId(),
                    matched.getDefaultType(), BigDecimal.ONE, true);
        }

        MonthlyCost mc = new MonthlyCost();
        mc.setMonth(req.getMonth());
        mc.setCostItemId(costItemId);
        mc.setItemName(name);
        mc.setAmount(req.getAmount());
        mc.setType(type);
        mc.setNote(req.getNote());
        mc.setSource("manual");
        monthlyCostMapper.insert(mc);

        audit.logAmount("CREATE_COST", "month=" + req.getMonth() + " item=" + name,
                "amount=" + mc.getAmount());
        recalcService.recalc(req.getMonth());
        return mc;
    }

    @Transactional
    public MonthlyCost update(Long id, CostReq req) {
        MonthlyCost mc = monthlyCostMapper.selectById(id);
        if (mc == null) {
            throw BizException.notFound("成本记录不存在: id=" + id);
        }
        if (req.getItemName() != null && !req.getItemName().isBlank()) {
            String name = req.getItemName().trim();
            if (!name.equals(mc.getItemName())) {
                mc.setItemName(name);
                CostItem matched = costItemService.findByExactName(name);
                mc.setCostItemId(matched == null ? null : matched.getId());
                if (matched != null) {
                    mappingRuleService.record(name, matched.getId(),
                            matched.getDefaultType(), BigDecimal.ONE, true);
                }
            }
        }
        if (req.getAmount() != null) {
            if (req.getAmount().compareTo(BigDecimal.ZERO) < 0) {
                throw BizException.badRequest("amount 必须 >= 0");
            }
            mc.setAmount(req.getAmount());
        }
        if (req.getType() != null && !req.getType().isBlank()) {
            if (!TYPES.contains(req.getType())) {
                throw BizException.badRequest("type 必须是 fixed/variable/one_time");
            }
            mc.setType(req.getType());
        }
        if (req.getNote() != null) {
            mc.setNote(req.getNote());
        }
        monthlyCostMapper.updateById(mc);
        audit.logAmount("UPDATE_COST", "id=" + id + " month=" + mc.getMonth() + " item=" + mc.getItemName(),
                "amount=" + mc.getAmount() + " type=" + mc.getType());
        recalcService.recalc(mc.getMonth());
        return mc;
    }

    @Transactional
    public void delete(Long id) {
        MonthlyCost mc = monthlyCostMapper.selectById(id);
        if (mc == null) {
            throw BizException.notFound("成本记录不存在: id=" + id);
        }
        monthlyCostMapper.deleteById(id);
        audit.logAmount("DELETE_COST", "id=" + id + " month=" + mc.getMonth() + " item=" + mc.getItemName(),
                "amount=" + mc.getAmount());
        recalcService.recalc(mc.getMonth());
    }

    /** 类型小计 + 合计（成本结构饼图）。 */
    public Map<String, Object> summary(String month) {
        Months.require(month);
        List<MonthlyCost> rows = byMonth(month);
        BigDecimal fixed = sumOf(rows, "fixed");
        BigDecimal variable = sumOf(rows, "variable");
        BigDecimal oneTime = sumOf(rows, "one_time");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("month", month);
        m.put("fixed", fixed);
        m.put("variable", variable);
        m.put("one_time", oneTime);
        m.put("total", fixed.add(variable).add(oneTime));
        return m;
    }

    /** 按月成本趋势（近 N 月三条线）。 */
    public List<Map<String, Object>> trend(String from, String to) {
        YearMonth fromYm = Months.require(from);
        YearMonth toYm = Months.require(to);
        if (fromYm.isAfter(toYm)) {
            throw BizException.badRequest("from 不能晚于 to");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (YearMonth ym = fromYm; !ym.isAfter(toYm); ym = ym.plusMonths(1)) {
            String m = Months.format(ym);
            List<MonthlyCost> rows = byMonth(m);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("month", m);
            e.put("fixed", sumOf(rows, "fixed"));
            e.put("variable", sumOf(rows, "variable"));
            e.put("one_time", sumOf(rows, "one_time"));
            e.put("total", rows.stream().map(r -> nz(r.getAmount()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
            out.add(e);
        }
        return out;
    }

    private List<MonthlyCost> byMonth(String month) {
        return monthlyCostMapper.selectList(
                new LambdaQueryWrapper<MonthlyCost>().eq(MonthlyCost::getMonth, month));
    }

    private static BigDecimal sumOf(List<MonthlyCost> rows, String type) {
        return rows.stream()
                .filter(r -> type.equals(r.getType()))
                .map(r -> nz(r.getAmount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    public static class CostReq {
        private String month;
        private String itemName;
        private BigDecimal amount;
        private String type;
        private String note;

        public String getMonth() {
            return month;
        }

        public void setMonth(String month) {
            this.month = month;
        }

        public String getItemName() {
            return itemName;
        }

        public void setItemName(String itemName) {
            this.itemName = itemName;
        }

        public BigDecimal getAmount() {
            return amount;
        }

        public void setAmount(BigDecimal amount) {
            this.amount = amount;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getNote() {
            return note;
        }

        public void setNote(String note) {
            this.note = note;
        }
    }
}
