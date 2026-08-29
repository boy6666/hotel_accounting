package com.hotel.accounting.controller;

import com.hotel.accounting.common.ApiResult;
import com.hotel.accounting.service.DashboardService;
import com.hotel.accounting.service.ReconcileInfo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 首页看板（03 §5.1-5.5）。全部只读。
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/overview")
    public ApiResult<Map<String, Object>> overview(@RequestParam String month) {
        return ApiResult.ok(dashboardService.overview(month));
    }

    @GetMapping("/trend")
    public ApiResult<Map<String, Object>> trend(@RequestParam String from, @RequestParam String to) {
        return ApiResult.ok(dashboardService.trend(from, to));
    }

    @GetMapping("/cost-structure")
    public ApiResult<Map<String, Object>> costStructure(@RequestParam String month) {
        return ApiResult.ok(dashboardService.costStructure(month));
    }

    @GetMapping("/channel-ratio")
    public ApiResult<Map<String, Object>> channelRatio(@RequestParam String month) {
        return ApiResult.ok(dashboardService.channelRatio(month));
    }

    @GetMapping("/reconcile")
    public ApiResult<Map<String, Object>> reconcile(@RequestParam String month) {
        ReconcileInfo ri = dashboardService.reconcile(month);
        return ApiResult.ok(OccupancyController.toReconcileMap(ri));
    }
}
