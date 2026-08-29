package com.hotel.accounting.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("hotel_config")
public class HotelConfig {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String hotelName;
    private String city;
    private BigDecimal defaultCommissionRate;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
