package com.hotel.accounting.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("channel_monthly")
public class ChannelMonthly {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String month;
    private Long channelId;
    private Integer nights;
    private BigDecimal revenue;
    private BigDecimal grossRevenue;
    private BigDecimal commission;
    private BigDecimal avgPrice;
    private String note;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
