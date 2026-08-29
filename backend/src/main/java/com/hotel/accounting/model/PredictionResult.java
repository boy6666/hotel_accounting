package com.hotel.accounting.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 预测结果 + LLM 解读（02 §3.15）。target_type=monthly|daily；metric 为预测指标；
 * engine=statistical|llm|hybrid。同一 (target_type,target) 允许多版（取 generated_at 最新）。
 */
@Data
@TableName("prediction_result")
public class PredictionResult {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String targetType;
    private String target;
    private String metric;
    private BigDecimal predictedValue;
    private String engine;
    private String modelVersion;
    private String llmInterpretation;
    private BigDecimal confidenceLow;
    private BigDecimal confidenceHigh;
    private LocalDateTime generatedAt;
    private LocalDateTime createdAt;
}
