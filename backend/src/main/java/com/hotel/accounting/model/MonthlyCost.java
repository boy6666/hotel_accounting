package com.hotel.accounting.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("monthly_cost")
public class MonthlyCost {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String month;
    private Long costItemId;
    private String itemName;
    private BigDecimal amount;
    private String type;
    private String note;
    private String source;
    private Long importBatchId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
