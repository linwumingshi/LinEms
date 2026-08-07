-- =====================================================================
-- EnergyX 储能管理平台 · alarm 域（energy-alarm 服务）
-- 60_alarm.sql —— 告警规则 / 告警记录 / 升级策略
-- 版本：v1.0    日期：2026-08-06
-- 设计依据：Phase1 §8（告警检测延迟 ≤3s）；ADR-006
-- 分片策略：规则单库；iot_alarm_record 按月分区 + 同步冗余至 ES es_alarm_log
-- =====================================================================

USE `es_alarm`;

-- ---------------------------------------------------------------------
-- 1. 告警规则
--    condition 示例（属性规则）：
--      {"metric":"temp","op":"GTE","value":60,"windowSec":60}
--      或（事件规则）：{"event":"bmsFault"}
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `iot_alarm_rule`;
CREATE TABLE `iot_alarm_rule` (
  `rule_id`        BIGINT       NOT NULL AUTO_INCREMENT,
  `tenant_id`      BIGINT       NOT NULL,
  `rule_code`      VARCHAR(64)  NOT NULL COMMENT '规则编码，如 ALM_TEMP_HIGH',
  `rule_name`      VARCHAR(128) NOT NULL,
  `product_id`     BIGINT                DEFAULT NULL COMMENT '作用产品（NULL=全局）',
  `device_id`      BIGINT                DEFAULT NULL COMMENT '作用设备（NULL=产品级）',
  `trigger_type`   TINYINT      NOT NULL COMMENT '1属性比较 2事件 3策略',
  `condition`      JSON         NOT NULL COMMENT '触发条件',
  `severity`       TINYINT      NOT NULL DEFAULT 3 COMMENT '1提示 2一般 3严重 4危急',
  `silence_seconds` INT         NOT NULL DEFAULT 300 COMMENT '静默期（合并/防抖）',
  `recovery`       JSON                  DEFAULT NULL COMMENT '恢复条件 {"metric":"temp","op":"LT","value":55}',
  `status`         TINYINT      NOT NULL DEFAULT 1 COMMENT '0停用 1启用',
  `description`    VARCHAR(256)          DEFAULT NULL,
  `create_by`      BIGINT                DEFAULT NULL,
  `create_time`    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`rule_id`),
  UNIQUE KEY `uk_rule_code` (`tenant_id`, `rule_code`),
  KEY `idx_rule_product` (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='告警规则';

-- ---------------------------------------------------------------------
-- 2. 告警记录（按月分区，同步 ES）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `iot_alarm_record`;
CREATE TABLE `iot_alarm_record` (
  `alarm_event_id` VARCHAR(64)  NOT NULL COMMENT '告警事件ID（雪花，幂等锚点）',
  `tenant_id`      BIGINT       NOT NULL,
  `device_id`      BIGINT       NOT NULL,
  `product_key`    VARCHAR(64)           DEFAULT NULL,
  `rule_id`        BIGINT                DEFAULT NULL,
  `rule_code`      VARCHAR(64)  NOT NULL,
  `level`          TINYINT      NOT NULL COMMENT '1提示 2一般 3严重 4危急',
  `type`           TINYINT      NOT NULL COMMENT '1属性 2事件 3策略',
  `status`         TINYINT      NOT NULL DEFAULT 0 COMMENT '0触发中 1已恢复 2已确认',
  `message`        VARCHAR(512) NOT NULL COMMENT '告警内容',
  `ext`            JSON                  DEFAULT NULL COMMENT '扩展：当前值/阈值等',
  `triggered_time` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `recovered_time` DATETIME(3)           DEFAULT NULL,
  `acked_by`       VARCHAR(64)           DEFAULT NULL,
  `ack_time`       DATETIME(3)           DEFAULT NULL,
  PRIMARY KEY (`alarm_event_id`, `triggered_time`),
  KEY `idx_alarm_device_time` (`device_id`, `triggered_time`),
  KEY `idx_alarm_status` (`status`, `level`),
  KEY `idx_alarm_tenant_time` (`tenant_id`, `triggered_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='告警记录'
PARTITION BY RANGE COLUMNS(`triggered_time`) (
  PARTITION p202607 VALUES LESS THAN ('2026-08-01'),
  PARTITION p202608 VALUES LESS THAN ('2026-09-01'),
  PARTITION p202609 VALUES LESS THAN ('2026-10-01'),
  PARTITION pMax   VALUES LESS THAN (MAXVALUE)
);

-- ---------------------------------------------------------------------
-- 3. 告警升级策略
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `iot_alarm_escalation`;
CREATE TABLE `iot_alarm_escalation` (
  `escalation_id`    BIGINT      NOT NULL AUTO_INCREMENT,
  `tenant_id`        BIGINT      NOT NULL,
  `rule_id`          BIGINT      NOT NULL,
  `level_from`       TINYINT     NOT NULL,
  `level_to`         TINYINT     NOT NULL COMMENT '升级目标等级',
  `escalate_after_min` INT       NOT NULL DEFAULT 10 COMMENT '持续时长后升级',
  `notify_channels`  JSON        NOT NULL COMMENT '通知渠道 [{"type":"sms","target":"..."}]',
  `status`           TINYINT     NOT NULL DEFAULT 1,
  `create_time`      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`escalation_id`),
  KEY `idx_escalation_rule` (`rule_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='告警升级策略';
