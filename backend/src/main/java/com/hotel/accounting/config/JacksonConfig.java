package com.hotel.accounting.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Jackson 统一配置：
 * <ul>
 *   <li>BigDecimal 序列化为字符串（防浮点，全局约定 05 §2.4）；反序列化兼容字符串/数字。</li>
 *   <li>LocalDate → yyyy-MM-dd；LocalDateTime → yyyy-MM-dd HH:mm:ss。</li>
 * </ul>
 */
@Configuration
public class JacksonConfig {

    public static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    public static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Bean
    public SimpleModule accountingJacksonModule() {
        SimpleModule module = new SimpleModule("accounting-jackson");

        JsonSerializer<BigDecimal> bigDecimalAsString = new JsonSerializer<>() {
            @Override
            public void serialize(BigDecimal value, JsonGenerator gen, SerializerProvider serializers)
                    throws IOException {
                if (value == null) {
                    gen.writeNull();
                } else if (value.scale() > 0) {
                    gen.writeString(value.setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString());
                } else {
                    gen.writeString(value.toPlainString());
                }
            }
        };
        module.addSerializer(BigDecimal.class, bigDecimalAsString);

        // 反序列化：接受字符串或数字的金额
        module.addDeserializer(BigDecimal.class, new com.fasterxml.jackson.databind.JsonDeserializer<>() {
            @Override
            public BigDecimal deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                String text = p.getValueAsString();
                if (text == null || text.isBlank()) {
                    return null;
                }
                try {
                    return new BigDecimal(text.trim());
                } catch (NumberFormatException e) {
                    throw new IOException("金额字段解析失败: " + text, e);
                }
            }
        });

        module.addSerializer(LocalDate.class, new LocalDateSerializer(DATE));
        module.addDeserializer(LocalDate.class, new LocalDateDeserializer(DATE));
        module.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(DATETIME));
        module.addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(DATETIME));

        return module;
    }
}
