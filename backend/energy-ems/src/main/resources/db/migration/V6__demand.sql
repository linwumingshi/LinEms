-- =====================================================================
-- EnergyX 储能管理平台 · ems 域（energy-ems 服务）
-- V6__demand.sql —— 需量管理（P1-2）
-- 版本：v1.5    日期：2026-08-11
-- 说明：站点需量配置（限值/费率）+ 每站每 15min 槽位检测留痕。
-- =====================================================================

CREATE TABLE `ems_demand_config` (
  `demand_config_id` BIGINT        NOT NULL AUTO_INCREMENT,
  `tenant_id`        BIGINT        NOT NULL,
  `station_id`       BIGINT        NOT NULL,
  `demand_limit_kw`  DECIMAL(10,2)          DEFAULT NULL COMMENT '需量限值 kW（>0 启用检测）',
  `demand_rate`      DECIMAL(8,4)           DEFAULT NULL COMMENT '需量费率 ¥/kW·月',
  `create_time`      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`demand_config_id`),
  UNIQUE KEY `uk_demand_config_station` (`tenant_id`, `station_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='需量管理站点配置';

CREATE TABLE `ems_demand_record` (
  `demand_record_id` BIGINT      NOT NULL AUTO_INCREMENT,
  `tenant_id`        BIGINT      NOT NULL,
  `station_id`       BIGINT      NOT NULL,
  `window_start`     DATETIME(3) NOT NULL COMMENT '槽位起点',
  `window_end`       DATETIME(3) NOT NULL COMMENT '槽位终点',
  `demand_kw`        DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '槽位实际需量（15min 平均功率 kW）',
  `limit_kw`         DECIMAL(10,2)          DEFAULT NULL COMMENT '限值快照 kW',
  `over_limit`       TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '是否超限',
  `shaved_kw`        DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '削峰放电功率 kW（未削峰=0）',
  `action`           VARCHAR(16) NOT NULL DEFAULT 'NONE' COMMENT 'NONE/SHED/SHED_FAILED/ALARM_ONLY',
  `create_time`      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`demand_record_id`),
  UNIQUE KEY `uk_demand_record_window` (`station_id`, `window_start`),
  KEY `idx_demand_record_station_time` (`station_id`, `window_start`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='需量检测槽位记录';
