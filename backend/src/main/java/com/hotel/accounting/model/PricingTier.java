package com.hotel.accounting.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("pricing_tier")
public class PricingTier {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private BigDecimal basePrice;
    private String applyDays;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private Integer active;
    private Integer sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
