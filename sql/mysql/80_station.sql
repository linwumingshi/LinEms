-- =====================================================================
-- EnergyX 储能管理平台 · station 域（energy-station 服务）
-- 80_station.sql —— 电站资产 / 电站-设备关联
-- 版本：v1.0    日期：2026-08-06
-- 说明：电站是资产树根（无 MQTT 接入）；设备实时状态权威源在 Redis（ADR-005），
--       历史在 TDengine，MySQL 不存实时状态。
-- =====================================================================

USE `es_station`;

-- ---------------------------------------------------------------------
-- 1. 电站（资产根）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `iot_station`;
CREATE TABLE `iot_station` (
  `station_id`        BIGINT       NOT NULL AUTO_INCREMENT,
  `tenant_id`         BIGINT       NOT NULL,
  `enterprise_id`     BIGINT       NOT NULL COMMENT '所属企业',
  `station_code`      VARCHAR(64)  NOT NULL COMMENT '电站编码',
  `station_name`      VARCHAR(128) NOT NULL,
  `address`           VARCHAR(256)          DEFAULT NULL,
  `longitude`         DECIMAL(10,6)          DEFAULT NULL,
  `latitude`          DECIMAL(10,6)          DEFAULT NULL,
  `install_capacity`  DECIMAL(12,3)          DEFAULT NULL COMMENT '装机容量 kWh',
  `pcs_capacity`      DECIMAL(12,3)          DEFAULT NULL COMMENT 'PCS 容量 kW',
  `battery_capacity`  DECIMAL(12,3)          DEFAULT NULL COMMENT '电池容量 kWh',
  `grid_type`         VARCHAR(16)           DEFAULT 'INDUSTRIAL' COMMENT '工商业/园区/电网侧',
  `status`            TINYINT      NOT NULL DEFAULT 1 COMMENT '0停运 1运行',
  `create_by`         BIGINT                 DEFAULT NULL,
  `create_time`       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `deleted`           TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (`station_id`),
  UNIQUE KEY `uk_station_code` (`tenant_id`, `station_code`),
  KEY `idx_station_enterprise` (`enterprise_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='电站资产';

-- ---------------------------------------------------------------------
-- 2. 电站-设备关联（多对多，设备主表在 es_device）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `iot_station_device`;
CREATE TABLE `iot_station_device` (
  `id`            BIGINT NOT NULL AUTO_INCREMENT,
  `station_id`    BIGINT NOT NULL,
  `device_id`     BIGINT NOT NULL,
  `relation_type` TINYINT NOT NULL DEFAULT 1 COMMENT '1直接接入 2间接归属',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_station_device` (`station_id`, `device_id`),
  KEY `idx_sd_device` (`device_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='电站-设备关联';
