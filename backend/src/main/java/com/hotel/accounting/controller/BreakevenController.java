package com.hotel.accounting.controller;

import com.hotel.accounting.common.ApiResult;
import com.hotel.accounting.service.BreakevenService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 回本测算（03 §11.1-11.6）：方案 CRUD + 现金流 + 敏感度。
 */
@RestController
@RequestMapping("/api/breakeven/scenarios")
public class BreakevenController {

    private final BreakevenService breakevenService;

    public BreakevenController(BreakevenService breakevenService) {
        this.breakevenService = breakevenService;
    }

    /** 11.1 方案列表。 */
    @GetMapping
    public ApiResult<Map<String, Object>> list() {
        return ApiResult.ok(breakevenService.list());
    }

    /** 11.2 新建方案（算月供 + 生成现金流）。 */
    @PostMapping
    public ApiResult<Map<String, Object>> create(@RequestBody BreakevenService.BreakevenReq req) {
        return ApiResult.ok(breakevenService.create(req));
    }

    /** 11.3 改参数（触发现金流重算）。 */
    @PutMapping("/{id}")
    public ApiResult<Map<String, Object>> update(@PathVariable Long id,
                                                 @RequestBody BreakevenService.BreakevenReq req) {
        return ApiResult.ok(breakevenService.update(id, req));
    }

    /** 11.4 删除（级联删现金流）。 */
    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable Long id) {
        breakevenService.delete(id);
        return ApiResult.ok();
    }

    /** 11.5 逐月现金流 + 回本月份。 */
    @GetMapping("/{id}/cashflow")
    public ApiResult<Map<String, Object>> cashflow(@PathVariable Long id) {
        return ApiResult.ok(breakevenService.cashflow(id));
    }

    /** 11.6 回本月份敏感度（15 行 + base）。 */
    @GetMapping("/{id}/sensitivity")
    public ApiResult<Map<String, Object>> sensitivity(@PathVariable Long id) {
        return ApiResult.ok(breakevenService.sensitivity(id));
    }
}
