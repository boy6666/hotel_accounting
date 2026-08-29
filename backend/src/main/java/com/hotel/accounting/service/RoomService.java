package com.hotel.accounting.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hotel.accounting.common.BizException;
import com.hotel.accounting.common.PageResult;
import com.hotel.accounting.mapper.RoomMapper;
import com.hotel.accounting.model.Room;
import com.hotel.accounting.util.AuditLogger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 房间字典服务（BE-02 §13.6-13.9）。未建档房号由房态/导入自动调用 findOrCreate 建档；启停联动重算。
 */
@Service
public class RoomService {

    private final RoomMapper roomMapper;
    private final RecalcService recalcService;
    private final AuditLogger audit;

    public RoomService(RoomMapper roomMapper, RecalcService recalcService, AuditLogger audit) {
        this.roomMapper = roomMapper;
        this.recalcService = recalcService;
        this.audit = audit;
    }

    public PageResult<Room> listRooms(Integer enabled, String keyword, long page, long pageSize) {
        LambdaQueryWrapper<Room> qw = new LambdaQueryWrapper<Room>()
                .orderByAsc(Room::getSortOrder).orderByAsc(Room::getRoomNo);
        if (enabled != null) {
            qw.eq(Room::getEnabled, enabled);
        }
        if (keyword != null && !keyword.isBlank()) {
            qw.like(Room::getRoomNo, keyword.trim());
        }
        List<Room> all = roomMapper.selectList(qw);
        long total = all.size();
        int fromIdx = (int) Math.min(total, (page - 1) * pageSize);
        int toIdx = (int) Math.min(total, fromIdx + pageSize);
        return PageResult.of(all.subList(fromIdx, toIdx), total, page, pageSize);
    }

    @Transactional
    public Room createRoom(RoomReq req) {
        requireNoBlank(req.getRoomNo(), "roomNo 不能为空");
        String roomNo = req.getRoomNo().trim();
        if (roomMapper.selectByRoomNo(roomNo) != null) {
            throw BizException.conflict("房间号已存在: " + roomNo);
        }
        Room r = new Room();
        r.setRoomNo(roomNo);
        r.setRoomType(req.getRoomType());
        r.setFloor(req.getFloor());
        r.setSortOrder(req.getSortOrder() == null ? roomMapper.maxSortOrder() + 1 : req.getSortOrder());
        r.setEnabled(req.getEnabled() == null ? 1 : (req.getEnabled() ? 1 : 0));
        roomMapper.insert(r);
        audit.log("CREATE_ROOM", "roomNo=" + roomNo);
        recalcService.recalcAllMonths();
        return r;
    }

    @Transactional
    public Room updateRoom(Long id, RoomReq req) {
        Room r = requireRoom(id);
        if (req.getRoomType() != null) {
            r.setRoomType(req.getRoomType());
        }
        if (req.getFloor() != null) {
            r.setFloor(req.getFloor());
        }
        if (req.getSortOrder() != null) {
            r.setSortOrder(req.getSortOrder());
        }
        if (req.getEnabled() != null) {
            r.setEnabled(req.getEnabled() ? 1 : 0);
        }
        if (req.getRoomNo() != null && !req.getRoomNo().equals(r.getRoomNo())) {
            throw BizException.badRequest("roomNo 只允许建档时设置（历史关联以 id 稳定）");
        }
        roomMapper.updateById(r);
        audit.log("UPDATE_ROOM", "room#" + id + " roomNo=" + r.getRoomNo());
        recalcService.recalcAllMonths();
        return r;
    }

    @Transactional
    public void disableRoom(Long id) {
        Room r = requireRoom(id);
        r.setEnabled(0);
        roomMapper.updateById(r);
        audit.log("DISABLE_ROOM", "room#" + id + " roomNo=" + r.getRoomNo());
        recalcService.recalcAllMonths();
    }

    /** 查房号；不存在则自动建档（房态登记/导入公共）。返回 always non-null。 */
    @Transactional
    public Room findOrCreateRoom(String roomNo, String roomType, String floor) {
        requireNoBlank(roomNo, "房号不能为空");
        String no = roomNo.trim();
        Room r = roomMapper.selectByRoomNo(no);
        if (r != null) {
            return r;
        }
        r = new Room();
        r.setRoomNo(no);
        r.setRoomType(roomType);
        r.setFloor(floor);
        r.setEnabled(1);
        r.setSortOrder(roomMapper.maxSortOrder() + 1);
        roomMapper.insert(r);
        audit.log("AUTO_CREATE_ROOM", "roomNo=" + no + " (未建档自动建档)");
        return r;
    }

    public Room requireRoom(Long id) {
        Room r = roomMapper.selectById(id);
        if (r == null) {
            throw BizException.notFound("房间不存在: id=" + id);
        }
        return r;
    }

    private static void requireNoBlank(String v, String msg) {
        if (v == null || v.isBlank()) {
            throw BizException.badRequest(msg);
        }
    }

    public static class RoomReq {
        private String roomNo;
        private String roomType;
        private String floor;
        private Integer sortOrder;
        private Boolean enabled;

        public String getRoomNo() {
            return roomNo;
        }

        public void setRoomNo(String roomNo) {
            this.roomNo = roomNo;
        }

        public String getRoomType() {
            return roomType;
        }

        public void setRoomType(String roomType) {
            this.roomType = roomType;
        }

        public String getFloor() {
            return floor;
        }

        public void setFloor(String floor) {
            this.floor = floor;
        }

        public Integer getSortOrder() {
            return sortOrder;
        }

        public void setSortOrder(Integer sortOrder) {
            this.sortOrder = sortOrder;
        }

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }
    }
}
