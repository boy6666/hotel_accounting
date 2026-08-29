package com.hotel.accounting.controller;

import com.hotel.accounting.common.ApiResult;
import com.hotel.accounting.common.PageResult;
import com.hotel.accounting.model.Room;
import com.hotel.accounting.service.RoomService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 房间字典（03 §13.6-13.9）。房号唯一；只允许建档时设置房号；停用软删联动重算。
 */
@RestController
@RequestMapping("/api/rooms")
public class RoomController {

    private final RoomService roomService;

    public RoomController(RoomService roomService) {
        this.roomService = roomService;
    }

    @GetMapping
    public ApiResult<PageResult<Room>> list(@RequestParam(required = false) Integer enabled,
                                            @RequestParam(required = false) String keyword,
                                            @RequestParam(defaultValue = "1") long page,
                                            @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResult.ok(roomService.listRooms(enabled, keyword, page, pageSize));
    }

    @PostMapping
    public ApiResult<Room> create(@RequestBody RoomService.RoomReq req) {
        return ApiResult.ok(roomService.createRoom(req));
    }

    @PutMapping("/{id}")
    public ApiResult<Room> update(@PathVariable Long id, @RequestBody RoomService.RoomReq req) {
        return ApiResult.ok(roomService.updateRoom(id, req));
    }

    @PostMapping("/{id}/disable")
    public ApiResult<Void> disable(@PathVariable Long id) {
        roomService.disableRoom(id);
        return ApiResult.ok();
    }
}
