package com.hotel.accounting.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("monthly_summary")
public class MonthlySummary {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String month;
    private BigDecimal revenue;
    private BigDecimal grossRevenue;
    private BigDecimal commission;
    private Integer nights;
    private BigDecimal adr;
    private Integer onlineNights;
    private Integer offlineNights;
    private BigDecimal occupancyRate;
    private BigDecimal totalCost;
    private BigDecimal profit;
    private String dataStatus;
    private String reconcileStatus;
    private String note;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
