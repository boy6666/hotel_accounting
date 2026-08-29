package com.hotel.accounting.controller;

import com.hotel.accounting.common.ApiResult;
import com.hotel.accounting.service.ProfitService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 利润分析（03 §8.1-8.2）：逐月利润表（含同比）+ 单月利润表头。只读。
 */
@RestController
@RequestMapping("/api/profit")
public class ProfitController {

    private final ProfitService profitService;

    public ProfitController(ProfitService profitService) {
        this.profitService = profitService;
    }

    @GetMapping("/monthly")
    public ApiResult<Map<String, Object>> monthly(@RequestParam String from, @RequestParam String to) {
        return ApiResult.ok(profitService.monthly(from, to));
    }

    @GetMapping("/summary")
    public ApiResult<Map<String, Object>> summary(@RequestParam String month) {
        return ApiResult.ok(profitService.summary(month));
    }
}
