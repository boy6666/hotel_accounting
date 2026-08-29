package com.hotel.accounting.controller;

import com.hotel.accounting.common.ApiResult;
import com.hotel.accounting.common.PageResult;
import com.hotel.accounting.model.MonthlyCost;
import com.hotel.accounting.service.CostService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 成本分析（03 §6）：月度成本明细 CRUD + 分类汇总 + 趋势。增删改后自动重算月度。
 */
@RestController
@RequestMapping("/api/costs")
public class CostController {

    private final CostService costService;

    public CostController(CostService costService) {
        this.costService = costService;
    }

    @GetMapping
    public ApiResult<PageResult<MonthlyCost>> list(@RequestParam(required = false) String month,
                                                   @RequestParam(required = false) String type,
                                                   @RequestParam(defaultValue = "1") long page,
                                                   @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResult.ok(costService.list(month, type, page, pageSize));
    }

    @PostMapping
    public ApiResult<MonthlyCost> create(@RequestBody CostService.CostReq req) {
        return ApiResult.ok(costService.create(req));
    }

    @PutMapping("/{id}")
    public ApiResult<MonthlyCost> update(@PathVariable Long id, @RequestBody CostService.CostReq req) {
        return ApiResult.ok(costService.update(id, req));
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable Long id) {
        costService.delete(id);
        return ApiResult.ok();
    }

    @GetMapping("/summary")
    public ApiResult<Map<String, Object>> summary(@RequestParam String month) {
        return ApiResult.ok(costService.summary(month));
    }

    @GetMapping("/trend")
    public ApiResult<List<Map<String, Object>>> trend(@RequestParam String from, @RequestParam String to) {
        return ApiResult.ok(costService.trend(from, to));
    }
}
