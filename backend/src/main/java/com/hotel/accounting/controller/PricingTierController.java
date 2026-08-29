package com.hotel.accounting.controller;

import com.hotel.accounting.common.ApiResult;
import com.hotel.accounting.model.PricingTier;
import com.hotel.accounting.service.PricingTierService;
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

/**
 * 档位价目（03 §10.1-10.4）。档位不在 Excel 中，必须设置页人工维护（二期定价引擎用）。
 */
@RestController
@RequestMapping("/api/pricing/tiers")
public class PricingTierController {

    private final PricingTierService tierService;

    public PricingTierController(PricingTierService tierService) {
        this.tierService = tierService;
    }

    @GetMapping
    public ApiResult<List<PricingTier>> list(@RequestParam(required = false) Integer active) {
        return ApiResult.ok(tierService.list(active));
    }

    @PostMapping
    public ApiResult<PricingTier> create(@RequestBody PricingTierService.TierReq req) {
        return ApiResult.ok(tierService.create(req));
    }

    @PutMapping("/{id}")
    public ApiResult<PricingTier> update(@PathVariable Long id, @RequestBody PricingTierService.TierReq req) {
        return ApiResult.ok(tierService.update(id, req));
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable Long id) {
        tierService.delete(id);
        return ApiResult.ok();
    }
}
