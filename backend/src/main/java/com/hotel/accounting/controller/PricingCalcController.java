package com.hotel.accounting.controller;

import com.hotel.accounting.common.ApiResult;
import com.hotel.accounting.service.PricingCalcService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 目标倒推（03 §10.8-10.10）：纯计算 targetPrice + 保存/读取倒推场景。
 */
@RestController
@RequestMapping("/api/pricing/calc")
public class PricingCalcController {

    private final PricingCalcService calcService;

    public PricingCalcController(PricingCalcService calcService) {
        this.calcService = calcService;
    }

    /** 10.8 纯计算，不落库。roomCount 缺省 = enabled 房间数。 */
    @GetMapping("/target")
    public ApiResult<Map<String, Object>> target(
            @RequestParam BigDecimal targetRevenue,
            @RequestParam BigDecimal targetOccupancy,
            @RequestParam(required = false) Integer roomCount,
            @RequestParam(required = false) BigDecimal daysPerMonth) {
        return ApiResult.ok(calcService.target(targetRevenue, targetOccupancy,
                roomCount, daysPerMonth));
    }

    /** 10.9 保存倒推参数/结果。 */
    @PostMapping("/scenarios")
    public ApiResult<Map<String, Object>> saveScenario(@RequestBody PricingCalcService.CalcReq req) {
        return ApiResult.ok(calcService.saveScenario(req));
    }

    /** 10.10 最近 20 条。 */
    @GetMapping("/scenarios")
    public ApiResult<Map<String, Object>> listScenarios() {
        return ApiResult.ok(calcService.listScenarios());
    }
}
