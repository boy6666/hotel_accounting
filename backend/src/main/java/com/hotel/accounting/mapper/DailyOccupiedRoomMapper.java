package com.hotel.accounting.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hotel.accounting.model.DailyOccupiedRoom;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

/**
 * 每日具体房间明细。复合主键 (biz_date, room_id)，全部自定义 SQL（缺行 = 空房）。
 */
public interface DailyOccupiedRoomMapper extends BaseMapper<DailyOccupiedRoom> {

    @Delete("delete from daily_occupied_room where biz_date = #{date}")
    int deleteByBizDate(@Param("date") LocalDate date);

    @Delete("delete from daily_occupied_room where biz_date between #{from} and #{to}")
    int deleteBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Insert("insert into daily_occupied_room(biz_date, room_id) values (#{date}, #{roomId})")
    int insertRow(@Param("date") LocalDate date, @Param("roomId") Long roomId);

    @Select("select room_id from daily_occupied_room where biz_date = #{date} order by room_id")
    List<Long> selectRoomIdsByDate(@Param("date") LocalDate date);

    /** 某日入住房间数 */
    @Select("select count(*) from daily_occupied_room where biz_date = #{date}")
    int countByDate(@Param("date") LocalDate date);

    /** 区间内明细（矩阵/聚合用） */
    @Select("select biz_date, room_id from daily_occupied_room " +
            "where biz_date between #{from} and #{to} order by biz_date, room_id")
    List<DailyOccupiedRoom> selectBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
