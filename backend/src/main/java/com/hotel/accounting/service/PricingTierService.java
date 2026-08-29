package com.hotel.accounting.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hotel.accounting.common.BizException;
import com.hotel.accounting.mapper.PricingTierMapper;
import com.hotel.accounting.model.PricingTier;
import com.hotel.accounting.util.AuditLogger;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * 档位价目 CRUD（BE-02/§10.1-10.4）。档位不在 Excel 中，必须设置页人工维护（二期定价建议用）。
 */
@Service
public class PricingTierService {

    private static final Set<String> APPLY_DAYS = Set.of("weekday", "weekend", "holiday", "all");

    private final PricingTierMapper pricingTierMapper;
    private final AuditLogger audit;

    public PricingTierService(PricingTierMapper pricingTierMapper, AuditLogger audit) {
        this.pricingTierMapper = pricingTierMapper;
        this.audit = audit;
    }

    public List<PricingTier> list(Integer active) {
        LambdaQueryWrapper<PricingTier> qw = new LambdaQueryWrapper<PricingTier>()
                .orderByAsc(PricingTier::getSortOrder).orderByAsc(PricingTier::getId);
        if (active != null) {
            qw.eq(PricingTier::getActive, active);
        }
        return pricingTierMapper.selectList(qw);
    }

    public PricingTier create(TierReq req) {
        if (req.getName() == null || req.getName().isBlank()) {
            throw BizException.badRequest("档位名不能为空");
        }
        if (req.getBasePrice() == null || req.getBasePrice().compareTo(BigDecimal.ZERO) < 0) {
            throw BizException.badRequest("basePrice 必须 >= 0");
        }
        String applyDays = req.getApplyDays() == null ? "weekday" : req.getApplyDays().trim();
        if (!APPLY_DAYS.contains(applyDays)) {
            throw BizException.badRequest("applyDays 必须是 weekday/weekend/holiday/all");
        }
        PricingTier t = new PricingTier();
        t.setName(req.getName().trim());
        t.setBasePrice(req.getBasePrice());
        t.setApplyDays(applyDays);
        t.setEffectiveFrom(req.getEffectiveFrom());
        t.setEffectiveTo(req.getEffectiveTo());
        t.setActive(req.getActive() == null ? 1 : (req.getActive() ? 1 : 0));
        Integer maxSort = pricingTierMapper.selectList(null).stream()
                .mapToInt(x -> x.getSortOrder() == null ? 0 : x.getSortOrder()).max().orElse(0);
        t.setSortOrder(req.getSortOrder() == null ? maxSort + 1 : req.getSortOrder());
        pricingTierMapper.insert(t);
        audit.log("CREATE_PRICING_TIER", "name=" + t.getName() + " base=" + t.getBasePrice());
        return t;
    }

    public PricingTier update(Long id, TierReq req) {
        PricingTier t = require(id);
        if (req.getName() != null && !req.getName().isBlank()) {
            t.setName(req.getName().trim());
        }
        if (req.getBasePrice() != null) {
            if (req.getBasePrice().compareTo(BigDecimal.ZERO) < 0) {
                throw BizException.badRequest("basePrice 必须 >= 0");
            }
            t.setBasePrice(req.getBasePrice());
        }
        if (req.getApplyDays() != null && !req.getApplyDays().isBlank()) {
            if (!APPLY_DAYS.contains(req.getApplyDays().trim())) {
                throw BizException.badRequest("applyDays 必须是 weekday/weekend/holiday/all");
            }
            t.setApplyDays(req.getApplyDays().trim());
        }
        if (req.getEffectiveFrom() != null) {
            t.setEffectiveFrom(req.getEffectiveFrom());
        }
        if (req.getEffectiveTo() != null) {
            t.setEffectiveTo(req.getEffectiveTo());
        }
        if (req.getActive() != null) {
            t.setActive(req.getActive() ? 1 : 0);
        }
        if (req.getSortOrder() != null) {
            t.setSortOrder(req.getSortOrder());
        }
        pricingTierMapper.updateById(t);
        audit.log("UPDATE_PRICING_TIER", "tier#" + id + " name=" + t.getName());
        return t;
    }

    public void delete(Long id) {
        PricingTier t = require(id);
        // 被建议价引用置 NULL 由 FK ON DELETE SET NULL 处理；档位无软删列，直接物理删
        pricingTierMapper.deleteById(id);
        audit.log("DELETE_PRICING_TIER", "name=" + t.getName());
    }

    public PricingTier require(Long id) {
        PricingTier t = pricingTierMapper.selectById(id);
        if (t == null) {
            throw BizException.notFound("档位不存在: id=" + id);
        }
        return t;
    }

    public static class TierReq {
        private String name;
        private BigDecimal basePrice;
        private String applyDays;
        private LocalDate effectiveFrom;
        private LocalDate effectiveTo;
        private Boolean active;
        private Integer sortOrder;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public BigDecimal getBasePrice() {
            return basePrice;
        }

        public void setBasePrice(BigDecimal basePrice) {
            this.basePrice = basePrice;
        }

        public String getApplyDays() {
            return applyDays;
        }

        public void setApplyDays(String applyDays) {
            this.applyDays = applyDays;
        }

        public LocalDate getEffectiveFrom() {
            return effectiveFrom;
        }

        public void setEffectiveFrom(LocalDate effectiveFrom) {
            this.effectiveFrom = effectiveFrom;
        }

        public LocalDate getEffectiveTo() {
            return effectiveTo;
        }

        public void setEffectiveTo(LocalDate effectiveTo) {
            this.effectiveTo = effectiveTo;
        }

        public Boolean getActive() {
            return active;
        }

        public void setActive(Boolean active) {
            this.active = active;
        }

        public Integer getSortOrder() {
            return sortOrder;
        }

        public void setSortOrder(Integer sortOrder) {
            this.sortOrder = sortOrder;
        }
    }
}
