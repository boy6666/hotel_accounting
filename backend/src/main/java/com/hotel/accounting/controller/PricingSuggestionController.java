package com.hotel.accounting.controller;

import com.hotel.accounting.common.ApiResult;
import com.hotel.accounting.service.PricingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * 临近日逐日建议价（03 §10.5-10.7）：engine 生成 / 区间列表 / 人工改价锁定。
 */
@RestController
@RequestMapping("/api/pricing/suggestions")
public class PricingSuggestionController {

    private final PricingService pricingService;

    public PricingSuggestionController(PricingService pricingService) {
        this.pricingService = pricingService;
    }

    /** 10.5 列表（未生成的日期不出现）。 */
    @GetMapping
    public ApiResult<Map<String, Object>> list(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        return ApiResult.ok(pricingService.list(from, to));
    }

    /** 10.6 生成临近日建议价（引擎）。 */
    @PostMapping("/generate")
    public ApiResult<Map<String, Object>> generate(@RequestBody GenReq req) {
        return ApiResult.ok(pricingService.generate(
                req.from == null ? null : req.from,
                req.to == null ? null : req.to));
    }

    /** 10.7 人工改价并锁定（source=manual，后续引擎不再覆盖）。 */
    @PutMapping("/{bizDate}")
    public ApiResult<Map<String, Object>> manualPut(@PathVariable LocalDate bizDate,
                                                    @RequestBody SugPut body) {
        return ApiResult.ok(pricingService.manualPut(bizDate,
                body == null ? null : body.suggestedPrice));
    }

    public static class GenReq {
        private LocalDate from;
        private LocalDate to;

        public LocalDate getFrom() {
            return from;
        }

        public void setFrom(LocalDate from) {
            this.from = from;
        }

        public LocalDate getTo() {
            return to;
        }

        public void setTo(LocalDate to) {
            this.to = to;
        }
    }

    public static class SugPut {
        private BigDecimal suggestedPrice;

        public BigDecimal getSuggestedPrice() {
            return suggestedPrice;
        }

        public void setSuggestedPrice(BigDecimal suggestedPrice) {
            this.suggestedPrice = suggestedPrice;
        }
    }
}
