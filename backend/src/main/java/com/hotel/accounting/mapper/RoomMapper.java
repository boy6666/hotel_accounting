package com.hotel.accounting.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hotel.accounting.model.Room;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface RoomMapper extends BaseMapper<Room> {

    @Select("select * from room where room_no = #{roomNo}")
    Room selectByRoomNo(@Param("roomNo") String roomNo);

    /** 可售房间数 = enabled=1 计数 */
    @Select("select count(*) from room where enabled = 1")
    long countEnabled();

    @Select("select COALESCE(max(sort_order), 0) from room")
    int maxSortOrder();
}
