package com.hotel.accounting.controller;

import com.hotel.accounting.common.ApiResult;
import com.hotel.accounting.service.PredictionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/**
 * 预测（03 §10.11-10.13 / §14.3-14.4）：月度生成（对旁车 ± 降级）+ 历史 + 日粒度。
 */
@RestController
@RequestMapping("/api/prediction")
public class PredictionController {

    private final PredictionService predictionService;

    public PredictionController(PredictionService predictionService) {
        this.predictionService = predictionService;
    }

    /** 10.11 生成月度预测（统计模型 + LLM 解读，落库）。 */
    @PostMapping("/generate")
    public ApiResult<Map<String, Object>> generate(@RequestBody GenReq req) {
        return ApiResult.ok(predictionService.generate(
                req == null ? null : req.month,
                req == null ? null : req.metric));
    }

    /** 10.12 预测历史（target 可省略取最近一个月）。 */
    @GetMapping("/results")
    public ApiResult<Map<String, Object>> results(@RequestParam(required = false) String target,
                                                  @RequestParam(required = false) String metric) {
        return ApiResult.ok(predictionService.results(target, metric));
    }

    /** 10.13 日粒度预测（date/month 二选一，month 缺省 = 今天所在月）。 */
    @GetMapping("/daily")
    public ApiResult<Map<String, Object>> daily(@RequestParam(required = false) LocalDate date,
                                                @RequestParam(required = false) String month) {
        return ApiResult.ok(predictionService.daily(date, month));
    }

    public static class GenReq {
        private String month;
        private String metric;

        public String getMonth() {
            return month;
        }

        public void setMonth(String month) {
            this.month = month;
        }

        public String getMetric() {
            return metric;
        }

        public void setMetric(String metric) {
            this.metric = metric;
        }
    }
}
