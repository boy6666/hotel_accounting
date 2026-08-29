package com.hotel.accounting.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 回本逐月现金流（02 §3.14）。running_balance 累计余额，首次 ≥0 即回本（上限 360 月）。
 */
@Data
@TableName("breakeven_cashflow")
public class BreakevenCashflow {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long scenarioId;
    private Integer monthSeq;
    private BigDecimal inflow;
    private BigDecimal outflow;
    private BigDecimal net;
    private BigDecimal runningBalance;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
