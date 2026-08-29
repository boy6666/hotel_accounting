package com.hotel.accounting.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.Data;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * 回本测算参数（02 §3.14）。monthly_payment 由后端按等额本息算好存；现金流在 breakeven_cashflow。
 */
@Data
@TableName("breakeven_scenario")
public class BreakevenScenario {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private BigDecimal investment;
    private BigDecimal ownCapital;
    private BigDecimal loanAmount;
    /**
     * 贷款年利率：DB 列 DECIMAL(6,4)（seed 0.0380），但全局 JacksonConfig 的 BigDecimal
     * 序列化统一 setScale(2)（05 §2.4 金额约定）会把 0.038 输出成 "0.04"，前端整对象回传
     * PUT 时月供会被按 4% 重算 —— 故本字段单独保留 4 位小数输出（round-trip 精度）。
     */
    @JsonSerialize(using = BreakevenScenario.LoanRateSerializer.class)
    private BigDecimal loanRate;
    private Integer loanYears;
    private BigDecimal monthlyPayment;
    private BigDecimal monthlyNetInflow;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** loanRate 固定 4 位小数文本（供字段序列化与 summarize 的 Map 输出共用）。 */
    public static String loanRateText(BigDecimal value) {
        if (value == null) {
            return null;
        }
        return value.setScale(4, RoundingMode.HALF_UP).toPlainString();
    }

    /** loanRate 专属序列化器：固定 4 位小数（其余金额仍走全局 2 位）。 */
    public static class LoanRateSerializer extends JsonSerializer<BigDecimal> {
        @Override
        public void serialize(BigDecimal value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {
            String text = loanRateText(value);
            if (text == null) {
                gen.writeNull();
            } else {
                gen.writeString(text);
            }
        }
    }
}
