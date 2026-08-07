-- =====================================================================
-- EnergyX 储能管理平台 · shadow 域（energy-shadow 服务）
-- 40_shadow.sql —— 设备影子（reported/desired + version 乐观锁）
-- 版本：v1.0    日期：2026-08-06
-- 设计依据：ADR-005/007（影子双文档 + 版本乐观锁；MySQL 权威源、Redis 热缓存）
-- 分片策略：按 device_id hash 分 16 表
-- =====================================================================

USE `es_shadow`;

-- ---------------------------------------------------------------------
-- 1. 影子主表（一设备一行）
--    reported：设备上报的最新属性快照
--    desired ：平台期望配置（设备离线时下发，上线后经 delta 同步）
--    更新采用乐观锁：UPDATE ... SET version=version+1, desired=? WHERE device_id=? AND version=?
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `iot_shadow`;
CREATE TABLE `iot_shadow` (
  `device_id`          BIGINT       NOT NULL COMMENT '设备ID（与设备表一致，PK即路由键）',
  `tenant_id`          BIGINT       NOT NULL,
  `reported`           JSON         NOT NULL COMMENT '设备上报属性 {"soc":85.2,"voltage":691.3,...}',
  `desired`            JSON         NOT NULL COMMENT '平台期望 {"power":50}（空对象 {} 表示无待同步）',
  `version`            INT          NOT NULL DEFAULT 0 COMMENT '影子版本（乐观锁）',
  `last_reported_time` DATETIME(3)           DEFAULT NULL,
  `last_desired_time`  DATETIME(3)           DEFAULT NULL,
  `create_time`        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`device_id`),
  KEY `idx_shadow_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备影子';

-- ---------------------------------------------------------------------
-- 2. 影子变更历史（仅关键变更落库，按月分表，控制膨胀）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `iot_shadow_history`;
CREATE TABLE `iot_shadow_history` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT,
  `device_id`     BIGINT       NOT NULL,
  `version`       INT          NOT NULL COMMENT '变更后版本',
  `snapshot`      JSON         NOT NULL COMMENT '变更后完整快照',
  `operator_type` TINYINT      NOT NULL COMMENT '1设备上报 2平台设置 3上线同步',
  `create_time`   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_shadow_hist_device` (`device_id`, `version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='影子变更历史';
