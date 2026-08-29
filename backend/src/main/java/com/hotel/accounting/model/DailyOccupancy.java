package com.hotel.accounting.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("daily_occupancy")
public class DailyOccupancy {
    @TableId(type = IdType.AUTO)
    private Long id;
    private LocalDate bizDate;
    private Integer occupiedRooms;
    private Integer totalRooms;
    private String source;
    private Long importBatchId;
    private String note;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
