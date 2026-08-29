package com.hotel.accounting.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hotel.accounting.mapper.ImportMappingRuleMapper;
import com.hotel.accounting.model.ImportMappingRule;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 智能归类学习规则（import_mapping_rule）维护。手录命中字典 / 导入确认时调用，is_manual=1 提高权重，供下次建议。
 */
@Service
public class MappingRuleService {

    private final ImportMappingRuleMapper ruleMapper;

    public MappingRuleService(ImportMappingRuleMapper ruleMapper) {
        this.ruleMapper = ruleMapper;
    }

    /** UPSERT 规则。costItemId 为 null 表示"建议新建费用项"（存 type，不落 cost_item_id）。 */
    public void record(String rawName, Long costItemId, String type, BigDecimal confidence, boolean manual) {
        if (rawName == null || rawName.isBlank()) {
            return;
        }
        ImportMappingRule rule = ruleMapper.selectOne(
                new LambdaQueryWrapper<ImportMappingRule>().eq(ImportMappingRule::getRawName, rawName.trim()));
        boolean isNew = rule == null;
        if (isNew) {
            rule = new ImportMappingRule();
            rule.setRawName(rawName.trim());
        }
        if (costItemId != null || rule.getCostItemId() == null) {
            rule.setCostItemId(costItemId);
        }
        if (type != null) {
            rule.setType(type);
        }
        rule.setConfidence(confidence == null ? BigDecimal.ONE : confidence);
        if (manual) {
            rule.setIsManual(1);
        } else if (rule.getIsManual() == null) {
            rule.setIsManual(0);
        }
        if (isNew) {
            ruleMapper.insert(rule);
        } else {
            ruleMapper.updateById(rule);
        }
    }

    /** 按原始名查询规则（含预建提示）。 */
    public ImportMappingRule findByRawName(String rawName) {
        if (rawName == null) {
            return null;
        }
        return ruleMapper.selectOne(new LambdaQueryWrapper<ImportMappingRule>()
                .eq(ImportMappingRule::getRawName, rawName.trim()));
    }
}
