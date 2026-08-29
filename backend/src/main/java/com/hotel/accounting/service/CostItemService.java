package com.hotel.accounting.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hotel.accounting.common.BizException;
import com.hotel.accounting.mapper.CostItemMapper;
import com.hotel.accounting.model.CostItem;
import com.hotel.accounting.util.AuditLogger;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 费用项字典（BE-02）：只读查询 + 导入/手录自动建档。（无独立维护页；改名不影响历史——流水存 item_name 快照）
 */
@Service
public class CostItemService {

    private final CostItemMapper costItemMapper;
    private final AuditLogger audit;

    public CostItemService(CostItemMapper costItemMapper, AuditLogger audit) {
        this.costItemMapper = costItemMapper;
        this.audit = audit;
    }

    public List<CostItem> list(Integer enabled, String keyword) {
        LambdaQueryWrapper<CostItem> qw = new LambdaQueryWrapper<CostItem>()
                .orderByAsc(CostItem::getId);
        if (enabled != null) {
            qw.eq(CostItem::getEnabled, enabled);
        }
        if (keyword != null && !keyword.isBlank()) {
            qw.like(CostItem::getName, keyword.trim());
        }
        return costItemMapper.selectList(qw);
    }

    /** 精确匹配费用项（ignore case/空白）；未命中返回 null。 */
    public CostItem findByExactName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String n = name.strip();
        return costItemMapper.selectList(new LambdaQueryWrapper<CostItem>()
                        .eq(CostItem::getName, n)).stream().findFirst()
                .orElseGet(() -> costItemMapper.selectList(null).stream()
                        .filter(c -> c.getName().equalsIgnoreCase(n))
                        .findFirst().orElse(null));
    }

    /** 未命中字典时自动建档（费用项自动化优先）。type 为空时默认 variable。 */
    public CostItem createIfMissing(String name, String type, Long viaBatchId) {
        CostItem exist = findByExactName(name);
        if (exist != null) {
            return exist;
        }
        CostItem item = new CostItem();
        item.setName(name.strip());
        item.setDefaultType(type == null || type.isBlank() ? "variable" : type);
        item.setEnabled(1);
        costItemMapper.insert(item);
        audit.log("AUTO_CREATE_COST_ITEM", "name=" + item.getName() + " type=" + item.getDefaultType()
                + " batch=" + viaBatchId);
        return item;
    }

    public CostItem require(Long id) {
        CostItem item = costItemMapper.selectById(id);
        if (item == null) {
            throw BizException.notFound("费用项不存在: id=" + id);
        }
        return item;
    }
}
