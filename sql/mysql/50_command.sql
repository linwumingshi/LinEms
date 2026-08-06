-- =====================================================================
-- 深圳三多能源储能管理平台 · command 域（energy-command 服务）
-- 50_command.sql —— Command Center：指令状态机 / ACK 留存
-- 版本：v1.0    日期：2026-08-06
-- 设计依据：ADR-009（QoS1 + commandId 业务幂等）；Phase1 §6.2 指令链路
-- 状态机：0 CREATED → 1 SENT → 2 DEVICE_RECEIVED → 3 EXECUTING → 4 SUCCESS / 5 FAILED / 6 TIMEOUT
-- 分片策略：iot_command 按 device_id hash 分 16 表（保证同设备指令串行）
-- =====================================================================

USE `es_command`;

-- ---------------------------------------------------------------------
-- 1. 指令主表
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `iot_command`;
CREATE TABLE `iot_command` (
  `command_id`     VARCHAR(64)  NOT NULL COMMENT '指令ID（雪花字符串，全局唯一，业务幂等锚点）',
  `tenant_id`      BIGINT       NOT NULL,
  `device_id`      BIGINT       NOT NULL COMMENT '目标设备',
  `product_key`    VARCHAR(64)  NOT NULL,
  `command_name`   VARCHAR(64)  NOT NULL COMMENT '物模型服务标识，如 setPower/startCharge',
  `command_type`   TINYINT      NOT NULL COMMENT '1读取 2控制',
  `params`         JSON                  DEFAULT NULL COMMENT '指令参数 {"power":50}',
  `state`          TINYINT      NOT NULL DEFAULT 0 COMMENT '0CREATED 1SENT 2DEVICE_RECEIVED 3EXECUTING 4SUCCESS 5FAILED 6TIMEOUT',
  `retry_count`    INT          NOT NULL DEFAULT 0,
  `max_retry`      INT          NOT NULL DEFAULT 3,
  `timeout_ms`     INT          NOT NULL DEFAULT 15000 COMMENT '指令超时',
  `sent_time`      DATETIME(3)           DEFAULT NULL,
  `received_time`  DATETIME(3)           DEFAULT NULL,
  `executing_time` DATETIME(3)           DEFAULT NULL,
  `finish_time`    DATETIME(3)           DEFAULT NULL,
  `result`         JSON                  DEFAULT NULL COMMENT '执行结果（成功回参）',
  `error_code`     VARCHAR(32)           DEFAULT NULL,
  `error_msg`      VARCHAR(256)          DEFAULT NULL,
  `create_by`      BIGINT                DEFAULT NULL COMMENT '发起人（人工）或0（策略引擎）',
  `create_time`    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`command_id`),
  KEY `idx_cmd_device_time` (`device_id`, `create_time`),
  KEY `idx_cmd_state_time` (`state`, `create_time`) COMMENT '超时扫描',
  KEY `idx_cmd_tenant_time` (`tenant_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='指令（Command Center）';

-- ---------------------------------------------------------------------
-- 2. 指令 ACK 留存（原始 ACK 报文，排查用，按月分表）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `iot_command_ack`;
CREATE TABLE `iot_command_ack` (
  `ack_id`     BIGINT      NOT NULL COMMENT '雪花ID',
  `command_id` VARCHAR(64) NOT NULL,
  `device_id`  BIGINT      NOT NULL,
  `ack_payload` JSON                 DEFAULT NULL COMMENT '设备 ACK 原始载荷',
  `ack_time`   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`ack_id`, `ack_time`),
  KEY `idx_ack_cmd` (`command_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='指令ACK留存'
PARTITION BY RANGE COLUMNS(`ack_time`) (
  PARTITION p202607 VALUES LESS THAN ('2026-08-01'),
  PARTITION p202608 VALUES LESS THAN ('2026-09-01'),
  PARTITION p202609 VALUES LESS THAN ('2026-10-01'),
  PARTITION pMax   VALUES LESS THAN (MAXVALUE)
);
