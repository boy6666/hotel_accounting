-- ============================================================================
-- 酒店记账 · 经营分析 · AI 定价系统  ——  MySQL 8 建库 DDL（权威）
-- 设计文档：docs/02-数据库设计.md
-- 约定：所有主数据（费用项/渠道/档位/全局配置）用户可增删改；金额一律 DECIMAL。
-- ============================================================================

-- 容器首次初始化时 mysql 客户端可能以 latin1 读入本文件，导致中文双重编码；
-- SET NAMES 强制本会话按 utf8mb4 解析后续字符串。
SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS hotel_accounting
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE hotel_accounting;

-- ---------------------------------------------------------------------------
-- 主数据
-- ---------------------------------------------------------------------------

CREATE TABLE sys_user (
  id            BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  username      VARCHAR(64)  NOT NULL,
  password_hash VARCHAR(255) NOT NULL COMMENT 'BCrypt',
  display_name  VARCHAR(64)  NULL,
  created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_user_username (username)
) COMMENT='登录账号';

CREATE TABLE hotel_config (
  id                    BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  hotel_name            VARCHAR(128) NOT NULL DEFAULT '',
  city                  VARCHAR(64)  NULL COMMENT '城市（天气预测，可空降级）',
  default_commission_rate DECIMAL(5,4) NOT NULL DEFAULT 0.1000 COMMENT '线上渠道默认佣金率 0.1200=12%',
  created_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) COMMENT='全局配置（单行方案 id=1）';
-- 注：可售房间数不在此表，由 room 表（enabled=1 计数）推导，供入住率/目标倒推/回本共用。

CREATE TABLE room (
  id            BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  room_no       VARCHAR(32) NOT NULL COMMENT '房号/名称，具体到每间，如 101',
  room_type     VARCHAR(64) NULL COMMENT '房型：大床房/双床房/家庭房…',
  floor         VARCHAR(16) NULL COMMENT '楼层：1F/2F…',
  enabled       TINYINT(1) NOT NULL DEFAULT 1 COMMENT '软删/停用（维修/长包不参与可售）',
  sort_order    INT NOT NULL DEFAULT 0,
  created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_room_no (room_no),
  KEY idx_room_enabled (enabled)
) COMMENT='房间字典（用户自定义 · 导入房号自动建档）';

CREATE TABLE channel (
  id              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  name            VARCHAR(64) NOT NULL,
  type            ENUM('online','offline') NOT NULL DEFAULT 'online',
  commission_rate DECIMAL(5,4) NOT NULL DEFAULT 0.0000 COMMENT '当前佣金率（用户可改）',
  enabled         TINYINT(1) NOT NULL DEFAULT 1 COMMENT '软删/停用',
  sort_order      INT NOT NULL DEFAULT 0,
  created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_channel_name (name),
  KEY idx_channel_enabled (enabled)
) COMMENT='渠道字典（用户自定义）';

CREATE TABLE cost_item (
  id           BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  name         VARCHAR(128) NOT NULL,
  default_type ENUM('fixed','variable','one_time') NOT NULL DEFAULT 'variable' COMMENT '固定/变动/一次性',
  enabled      TINYINT(1) NOT NULL DEFAULT 1 COMMENT '软删/停用',
  created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_cost_item_name (name),
  KEY idx_cost_item_enabled (enabled)
) COMMENT='费用项字典（用户自定义）';

CREATE TABLE pricing_tier (
  id              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  name            VARCHAR(64) NOT NULL,
  base_price      DECIMAL(10,2) NOT NULL,
  apply_days      VARCHAR(32) NOT NULL DEFAULT 'weekday' COMMENT 'weekday/weekend/holiday/all',
  effective_from  DATE NULL,
  effective_to    DATE NULL,
  active          TINYINT(1) NOT NULL DEFAULT 1 COMMENT '启用/停用',
  sort_order      INT NOT NULL DEFAULT 0,
  created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_tier_active (active)
) COMMENT='档位价目（用户自定义）';

-- ---------------------------------------------------------------------------
-- 流水
-- ---------------------------------------------------------------------------

CREATE TABLE monthly_cost (
  id              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  month           CHAR(7) NOT NULL COMMENT 'YYYY-MM',
  cost_item_id    BIGINT UNSIGNED NULL,
  item_name       VARCHAR(128) NOT NULL COMMENT '快照（导入/手录原名，字典改名不影响历史）',
  amount          DECIMAL(12,2) NOT NULL DEFAULT 0,
  type            ENUM('fixed','variable','one_time') NOT NULL COMMENT '当月分类（可覆盖字典默认）',
  note            VARCHAR(255) NULL,
  source          ENUM('manual','import','mapping') NOT NULL DEFAULT 'manual',
  import_batch_id BIGINT UNSIGNED NULL,
  created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_cost_month (month),
  KEY idx_cost_type (type),
  CONSTRAINT fk_monthly_cost_item FOREIGN KEY (cost_item_id) REFERENCES cost_item(id) ON DELETE SET NULL
) COMMENT='月度成本明细';

CREATE TABLE daily_occupancy (
  id              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  biz_date        DATE NOT NULL COMMENT '业务日',
  occupied_rooms  INT NOT NULL DEFAULT 0 COMMENT '当日入住房间数（= daily_occupied_room 计数，可重算冗余）',
  total_rooms     INT NOT NULL DEFAULT 0 COMMENT '当日可售房间数快照（room.enabled 计数）',
  source          ENUM('manual','import') NOT NULL DEFAULT 'manual',
  import_batch_id BIGINT UNSIGNED NULL,
  note            VARCHAR(255) NULL,
  created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_occ_date (biz_date)
) COMMENT='每日房态（按日聚合 · 明细在 daily_occupied_room）';

CREATE TABLE daily_occupied_room (
  biz_date    DATE NOT NULL COMMENT '业务日',
  room_id     BIGINT UNSIGNED NOT NULL COMMENT '当日实际入住的具体房间',
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (biz_date, room_id),
  KEY idx_dor_room (room_id),
  CONSTRAINT fk_dor_room FOREIGN KEY (room_id) REFERENCES room(id) ON DELETE CASCADE
) COMMENT='每日实际入住的具体房间（biz_date+room_id 唯一；缺行=空房）';

CREATE TABLE channel_monthly (
  id           BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  month        CHAR(7) NOT NULL COMMENT 'YYYY-MM',
  channel_id   BIGINT UNSIGNED NOT NULL,
  nights       INT NOT NULL DEFAULT 0,
  revenue      DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '到手价收入',
  gross_revenue DECIMAL(12,2) NULL COMMENT '挂牌价收入 = 到手/(1-佣金率)',
  commission   DECIMAL(12,2) NOT NULL DEFAULT 0,
  avg_price    DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '到手均价 = revenue/nights',
  note         VARCHAR(255) NULL,
  created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_ch_month (month, channel_id),
  KEY idx_ch_month (month),
  CONSTRAINT fk_chm_channel FOREIGN KEY (channel_id) REFERENCES channel(id)
) COMMENT='渠道×月统计（到手价口径）';

CREATE TABLE monthly_summary (
  id               BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  month            CHAR(7) NOT NULL,
  revenue          DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '到手总收入',
  gross_revenue    DECIMAL(12,2) NULL,
  commission       DECIMAL(12,2) NOT NULL DEFAULT 0,
  nights           INT NOT NULL DEFAULT 0,
  adr              DECIMAL(10,2) NOT NULL DEFAULT 0,
  online_nights    INT NOT NULL DEFAULT 0,
  offline_nights   INT NOT NULL DEFAULT 0,
  occupancy_rate   DECIMAL(5,2) NULL COMMENT '85.00 = 85%',
  total_cost       DECIMAL(12,2) NOT NULL DEFAULT 0,
  profit           DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT 'profit = revenue - total_cost',
  data_status      ENUM('manual','imported','computed') NOT NULL DEFAULT 'computed',
  reconcile_status ENUM('none','matched','diff','unchecked') NOT NULL DEFAULT 'unchecked' COMMENT '房态对账',
  note             VARCHAR(255) NULL,
  created_at       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_sum_month (month)
) COMMENT='月度汇总头（可重算冗余，明细变动后主后端刷新）';

-- ---------------------------------------------------------------------------
-- 辅助：导入 + 智能归类
-- ---------------------------------------------------------------------------

CREATE TABLE import_batch (
  id            BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  template_type ENUM('cost','occupancy','sales') NOT NULL COMMENT '三张表',
  month         CHAR(7) NOT NULL,
  file_name     VARCHAR(255) NOT NULL,
  file_path     VARCHAR(512) NULL,
  status        ENUM('uploaded','parsed','mapped','confirmed','failed') NOT NULL DEFAULT 'uploaded',
  total_rows    INT NOT NULL DEFAULT 0,
  failed_rows   INT NOT NULL DEFAULT 0,
  raw_name      VARCHAR(255) NULL COMMENT '识别到的名称摘要',
  error_message VARCHAR(1000) NULL,
  created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_imp_month (month)
) COMMENT='Excel 导入批次状态机';

CREATE TABLE import_mapping_rule (
  id          BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  raw_name    VARCHAR(255) NOT NULL,
  cost_item_id BIGINT UNSIGNED NULL,
  type        ENUM('fixed','variable','one_time') NULL,
  confidence  DECIMAL(5,4) NOT NULL DEFAULT 0.0000,
  is_manual   TINYINT(1) NOT NULL DEFAULT 0 COMMENT '用户手动确认过',
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_map_raw_name (raw_name),
  CONSTRAINT fk_map_item FOREIGN KEY (cost_item_id) REFERENCES cost_item(id) ON DELETE SET NULL
) COMMENT='智能归类学习规则';

-- ---------------------------------------------------------------------------
-- 衍生：定价 / 回本 / 预测
-- ---------------------------------------------------------------------------

CREATE TABLE pricing_suggestion (
  id                 BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  biz_date           DATE NOT NULL,
  tier_id            BIGINT UNSIGNED NULL,
  suggested_price    DECIMAL(10,2) NOT NULL,
  occupancy_forecast DECIMAL(5,2) NULL,
  is_weekend         TINYINT(1) NOT NULL DEFAULT 0,
  source             ENUM('manual','engine','llm') NOT NULL DEFAULT 'engine',
  generated_at       DATETIME NULL,
  created_at         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_suggest_date (biz_date),
  CONSTRAINT fk_suggest_tier FOREIGN KEY (tier_id) REFERENCES pricing_tier(id) ON DELETE SET NULL
) COMMENT='临近日逐日建议价';

CREATE TABLE price_calc_scenario (
  id               BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  name             VARCHAR(128) NULL,
  target_revenue   DECIMAL(12,2) NOT NULL,
  target_occupancy DECIMAL(5,2) NOT NULL COMMENT '%',
  room_count       INT NOT NULL,
  result_price     DECIMAL(10,2) NOT NULL,
  created_at       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) COMMENT='目标倒推计算器存参/结果';

CREATE TABLE breakeven_scenario (
  id                  BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  name                VARCHAR(128) NOT NULL,
  investment          DECIMAL(14,2) NOT NULL,
  own_capital         DECIMAL(14,2) NOT NULL DEFAULT 0,
  loan_amount         DECIMAL(14,2) NOT NULL DEFAULT 0,
  loan_rate           DECIMAL(6,4)  NOT NULL DEFAULT 0.0000 COMMENT '年利率 0.038=3.8%',
  loan_years          INT NOT NULL DEFAULT 10,
  monthly_payment     DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '等额本息月供',
  monthly_net_inflow  DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '月净流入（不含月供）',
  created_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) COMMENT='回本测算参数';

CREATE TABLE breakeven_cashflow (
  id             BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  scenario_id    BIGINT UNSIGNED NOT NULL,
  month_seq      INT NOT NULL COMMENT '第N月',
  inflow         DECIMAL(12,2) NOT NULL DEFAULT 0,
  outflow        DECIMAL(12,2) NOT NULL DEFAULT 0,
  net            DECIMAL(12,2) NOT NULL DEFAULT 0,
  running_balance DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '累计余额（首次>=0 即回本）',
  remark         VARCHAR(255) NULL,
  created_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_cf_scene_seq (scenario_id, month_seq),
  CONSTRAINT fk_cf_scenario FOREIGN KEY (scenario_id) REFERENCES breakeven_scenario(id) ON DELETE CASCADE
) COMMENT='回本逐月现金流';

CREATE TABLE prediction_result (
  id                BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  target_type       ENUM('monthly','daily') NOT NULL DEFAULT 'monthly',
  target            VARCHAR(10) NOT NULL COMMENT 'YYYY-MM 或 YYYY-MM-DD',
  metric            ENUM('revenue','nights','occupancy_rate','adr','price') NOT NULL,
  predicted_value   DECIMAL(12,2) NOT NULL,
  engine            VARCHAR(64) NOT NULL DEFAULT 'statistical' COMMENT 'statistical/llm/hybrid',
  model_version     VARCHAR(64) NULL,
  llm_interpretation TEXT NULL,
  confidence_low    DECIMAL(12,2) NULL,
  confidence_high   DECIMAL(12,2) NULL,
  generated_at      DATETIME NOT NULL,
  created_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_pred_target (target_type, target)
) COMMENT='预测结果+LLM解读';

CREATE TABLE app_setting (
  id         BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  skey       VARCHAR(128) NOT NULL,
  svalue     VARCHAR(512) NOT NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_setting_key (skey)
) COMMENT='通用 KV';
