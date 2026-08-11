-- =====================================================================
-- EnergyX 储能管理平台 · ems 域（energy-ems 服务）
-- V5__revenue_meta.sql —— 收益核算电站投资元数据（P1-1）
-- 版本：v1.4    日期：2026-08-11
-- 说明：ROI/回本周期数据源。投资额/投运日期在收益页录入，station_id 唯一。
-- =====================================================================

CREATE TABLE `ems_station_meta` (
  `station_meta_id`   BIGINT        NOT NULL AUTO_INCREMENT,
  `tenant_id`         BIGINT        NOT NULL,
  `station_id`        BIGINT        NOT NULL,
  `investment_amount` DECIMAL(12,2)          DEFAULT NULL COMMENT '投资额 元',
  `install_date`      DATE                   DEFAULT NULL COMMENT '投运日期',
  `create_time`       DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`       DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`station_meta_id`),
  UNIQUE KEY `uk_station_meta_station` (`station_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='收益核算电站投资元数据';
