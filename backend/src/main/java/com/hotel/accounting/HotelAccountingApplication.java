package com.hotel.accounting;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 酒店记账 · 经营分析 · AI 定价系统 —— Java 主后端入口。
 * <p>一期范围：BE-01 ~ BE-08（认证/主数据/成本/房态/渠道/月度汇总/看板/导入闭环）。</p>
 * <p>约定：数据库由 db/schema.sql 手动初始化，应用内不做自动建表，避免与权威 DDL 分叉。</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@MapperScan("com.hotel.accounting.mapper")
public class HotelAccountingApplication {

    public static void main(String[] args) {
        SpringApplication.run(HotelAccountingApplication.class, args);
    }
}
