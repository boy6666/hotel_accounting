package com.hotel.accounting.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 临近日逐日建议价（02 §3.12）。每 biz_date 一行（UNIQUE），source 区分 引擎/手改/LLM。
 */
@Data
@TableName("pricing_suggestion")
public class PricingSuggestion {
    @TableId(type = IdType.AUTO)
    private Long id;
    private LocalDate bizDate;
    private Long tierId;
    private BigDecimal suggestedPrice;
    private BigDecimal occupancyForecast;
    private Integer isWeekend;
    private String source;
    private LocalDateTime generatedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
