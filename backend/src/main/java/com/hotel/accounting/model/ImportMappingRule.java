package com.hotel.accounting.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("import_mapping_rule")
public class ImportMappingRule {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String rawName;
    private Long costItemId;
    private String type;
    private BigDecimal confidence;
    private Integer isManual;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
