package com.hotel.accounting.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 目标倒推计算器存参/结果（02 §3.13）。
 */
@Data
@TableName("price_calc_scenario")
public class PriceCalcScenario {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private BigDecimal targetRevenue;
    private BigDecimal targetOccupancy;
    private Integer roomCount;
    private BigDecimal resultPrice;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
