package com.hotel.accounting.model;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 每日实际入住的具体房间。复合主键 (biz_date, room_id)，不走 MyBatis-Plus CRUD，全部自定义 SQL。
 */
@Data
@TableName("daily_occupied_room")
public class DailyOccupiedRoom {
    private LocalDate bizDate;
    private Long roomId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
