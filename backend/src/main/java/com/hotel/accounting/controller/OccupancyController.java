package com.hotel.accounting.controller;

import com.hotel.accounting.common.ApiResult;
import com.hotel.accounting.service.OccupancyService;
import com.hotel.accounting.service.ReconcileInfo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 房态·入住率（03 §9.1-9.7）：按具体房间登记 + 房间×日期矩阵 + 批量补录 + 对账 + 平日/周末拆分。
 */
@RestController
@RequestMapping("/api/occupancy")
public class OccupancyController {

    private final OccupancyService occupancyService;

    public OccupancyController(OccupancyService occupancyService) {
        this.occupancyService = occupancyService;
    }

    @GetMapping("/daily")
    public ApiResult<Map<String, Object>> daily(@RequestParam String month) {
        return ApiResult.ok(occupancyService.daily(month));
    }

    @PutMapping("/day-rooms")
    public ApiResult<Map<String, Object>> putDayRooms(@RequestBody OccupancyService.DayRoomsReq req) {
        return ApiResult.ok(occupancyService.putDayRooms(req));
    }

    @GetMapping("/day-rooms")
    public ApiResult<List<Map<String, Object>>> dayRooms(@RequestParam LocalDate bizDate) {
        return ApiResult.ok(occupancyService.dayRooms(bizDate));
    }

    @GetMapping("/matrix")
    public ApiResult<Map<String, Object>> matrix(@RequestParam String month) {
        return ApiResult.ok(occupancyService.matrix(month));
    }

    @PostMapping("/batch")
    public ApiResult<Map<String, Object>> batch(@RequestBody OccupancyService.BatchReq req) {
        return ApiResult.ok(occupancyService.batch(req));
    }

    @GetMapping("/reconcile")
    public ApiResult<Map<String, Object>> reconcile(@RequestParam String month) {
        ReconcileInfo ri = occupancyService.reconcile(month);
        return ApiResult.ok(toReconcileMap(ri));
    }

    @GetMapping("/workday-rate")
    public ApiResult<Map<String, Object>> workdayRate(@RequestParam String month) {
        return ApiResult.ok(occupancyService.workdayRate(month));
    }

    public static Map<String, Object> toReconcileMap(ReconcileInfo ri) {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("month", ri.getMonth());
        m.put("reconcileStatus", ri.getStatus());
        m.put("occupancyNights", ri.getOccupancyNights());
        m.put("channelNights", ri.getChannelNights());
        m.put("diff", ri.getDiff());
        m.put("detailChannels", ri.getDetailChannels());
        return m;
    }
}
