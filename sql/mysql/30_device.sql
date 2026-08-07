-- =====================================================================
-- EnergyX 储能管理平台 · device 域（energy-device 服务）
-- 30_device.sql —— 设备主表 / 凭据 / 证书 / 在线记录 / 分组标签
-- 版本：v1.0    日期：2026-08-06
-- 设计依据：决策 A.3.1（设备树统一建模，邻接表 + 物化路径）；ADR-006
-- 分片策略：es_device 库按 tenant_id 分库；iot_device* 按 device_id hash 分 16 表
--          （生产模板见 sql/mysql/sharding/；本文件为逻辑基表）
-- =====================================================================

USE `es_device`;

-- ---------------------------------------------------------------------
-- 1. 设备主表（储能柜/电池簇/PCS/BMS/EMS/电表 统一建模，电芯不建表）
--    层级：电站(iot_station) → 设备树根(储能柜) → 簇/PCS/BMS（parent_id + path）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `iot_device`;
CREATE TABLE `iot_device` (
  `device_id`        BIGINT       NOT NULL COMMENT '设备ID（雪花）',
  `tenant_id`        BIGINT       NOT NULL COMMENT '租户',
  `enterprise_id`    BIGINT                DEFAULT NULL COMMENT '所属企业',
  `station_id`       BIGINT                DEFAULT NULL COMMENT '所属电站',
  `product_key`      VARCHAR(64)  NOT NULL COMMENT '产品标识（认证与路由锚点）',
  `device_name`      VARCHAR(128) NOT NULL COMMENT '设备名',
  `device_type`      VARCHAR(32)  NOT NULL COMMENT 'ENERGY_CABINET/BATTERY_CLUSTER/PCS/BMS/EMS/EDGE_GW',
  `parent_id`        BIGINT       NOT NULL DEFAULT 0 COMMENT '父设备ID（0=根）',
  `path`             VARCHAR(768) NOT NULL DEFAULT '/' COMMENT '物化路径 /柜ID/簇ID/，支持子树查询',
  `level`            TINYINT      NOT NULL DEFAULT 1 COMMENT '设备树层级',
  `sort`             INT          NOT NULL DEFAULT 0,
  `status`           TINYINT      NOT NULL DEFAULT 0 COMMENT '设备状态：0未注册 1未激活 2已激活(离线) 3在线 4禁用 5封禁',
  `firmware_version` VARCHAR(64)           DEFAULT NULL,
  `protocol`         VARCHAR(16)  NOT NULL DEFAULT 'MQTT',
  `broker_node`      VARCHAR(64)           DEFAULT NULL COMMENT '当前连接的 Broker 节点（热数据，权威源在 Redis）',
  `last_online_time` DATETIME(3)           DEFAULT NULL COMMENT '最近上线时间',
  `last_offline_time` DATETIME(3)          DEFAULT NULL,
  `online_seconds`   BIGINT       NOT NULL DEFAULT 0 COMMENT '累计在线秒数',
  `mac`              VARCHAR(64)           DEFAULT NULL,
  `ip`               VARCHAR(64)           DEFAULT NULL,
  `create_by`        BIGINT                DEFAULT NULL,
  `create_time`      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `deleted`          TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (`device_id`),
  UNIQUE KEY `uk_device_name` (`tenant_id`, `product_key`, `device_name`, `deleted`),
  KEY `idx_device_station` (`station_id`),
  KEY `idx_device_parent` (`parent_id`),
  KEY `idx_device_enterprise` (`enterprise_id`),
  KEY `idx_device_path` (`path`(191)),
  KEY `idx_device_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备主表（统一设备树）';

-- ---------------------------------------------------------------------
-- 2. 设备凭据（与设备分表解耦，安全：认证钩子只读凭据）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `iot_device_credential`;
CREATE TABLE `iot_device_credential` (
  `id`              BIGINT       NOT NULL AUTO_INCREMENT,
  `device_id`       BIGINT       NOT NULL,
  `tenant_id`       BIGINT       NOT NULL,
  `device_secret`   VARCHAR(128) NOT NULL COMMENT '设备密钥（HMAC 签名用，明文仅认证链可见）',
  `auth_status`     TINYINT      NOT NULL DEFAULT 1 COMMENT '1正常 2吊销',
  `fail_count`      INT          NOT NULL DEFAULT 0 COMMENT '连续认证失败次数（封禁判定）',
  `last_auth_time`  DATETIME(3)           DEFAULT NULL,
  `expire_time`     DATETIME(3)           DEFAULT NULL COMMENT '凭据过期时间',
  `create_time`     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cred_device` (`device_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备凭据';

-- ---------------------------------------------------------------------
-- 3. 设备证书（TLS 双向认证，可选）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `iot_device_certificate`;
CREATE TABLE `iot_device_certificate` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT,
  `device_id`    BIGINT       NOT NULL,
  `cert_type`    VARCHAR(16)  NOT NULL DEFAULT 'X509',
  `cert_cn`      VARCHAR(128) NOT NULL COMMENT '证书主体CN',
  `cert_content` TEXT         NOT NULL COMMENT '证书 PEM 内容',
  `valid_from`   DATETIME(3)           DEFAULT NULL,
  `valid_to`     DATETIME(3)           DEFAULT NULL,
  `status`       TINYINT      NOT NULL DEFAULT 1,
  `create_time`  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cert_device_type` (`device_id`, `cert_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备证书';

-- ---------------------------------------------------------------------
-- 4. 上下线记录（按月分区，30 天内在线状态以 Redis 为准，此表做审计）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `iot_device_online_record`;
CREATE TABLE `iot_device_online_record` (
  `record_id`   BIGINT      NOT NULL COMMENT '雪花ID',
  `device_id`   BIGINT      NOT NULL,
  `tenant_id`   BIGINT      NOT NULL,
  `event_type`  TINYINT     NOT NULL COMMENT '1上线 2离线',
  `reason`      VARCHAR(32)          DEFAULT NULL COMMENT 'NORMAL/HEARTBEAT_TIMEOUT/DUPLICATE_CLIENT/KICK',
  `ip`          VARCHAR(64)          DEFAULT NULL,
  `broker_node` VARCHAR(64)          DEFAULT NULL,
  `report_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`record_id`, `report_time`),
  KEY `idx_online_device_time` (`device_id`, `report_time`),
  KEY `idx_online_tenant_time` (`tenant_id`, `report_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备上下线记录'
PARTITION BY RANGE COLUMNS(`report_time`) (
  PARTITION p202607 VALUES LESS THAN ('2026-08-01'),
  PARTITION p202608 VALUES LESS THAN ('2026-09-01'),
  PARTITION p202609 VALUES LESS THAN ('2026-10-01'),
  PARTITION pMax   VALUES LESS THAN (MAXVALUE)
);

-- ---------------------------------------------------------------------
-- 5. 设备分组 / 标签（业务维度，非物理拓扑）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `iot_device_group`;
CREATE TABLE `iot_device_group` (
  `group_id`    BIGINT      NOT NULL AUTO_INCREMENT,
  `tenant_id`   BIGINT      NOT NULL,
  `parent_id`   BIGINT      NOT NULL DEFAULT 0,
  `group_name`  VARCHAR(64) NOT NULL,
  `description` VARCHAR(256)         DEFAULT NULL,
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`group_id`),
  KEY `idx_group_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备分组';

DROP TABLE IF EXISTS `iot_device_group_relation`;
CREATE TABLE `iot_device_group_relation` (
  `id`        BIGINT NOT NULL AUTO_INCREMENT,
  `group_id`  BIGINT NOT NULL,
  `device_id` BIGINT NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_group_device` (`group_id`, `device_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备-分组关联';

DROP TABLE IF EXISTS `iot_device_tag`;
CREATE TABLE `iot_device_tag` (
  `id`        BIGINT      NOT NULL AUTO_INCREMENT,
  `device_id` BIGINT      NOT NULL,
  `tag_key`   VARCHAR(64) NOT NULL,
  `tag_value` VARCHAR(256)         DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_device_tag` (`device_id`, `tag_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备标签(k=v)';
