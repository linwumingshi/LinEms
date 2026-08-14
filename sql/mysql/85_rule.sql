-- =====================================================================
-- EnergyX 储能管理平台 · rule 域（energy-rule 服务）
-- 85_rule.sql —— 场景联动规则 / 规则执行日志
-- 版本：v1.0    日期：2026-08-14
-- 设计依据：Phase11-场景联动与规则编排设计.md §5
-- 分片策略：规则单库；iot_scene_exec_log 按月分区（执行日志量大，保留 30 天，归档 ES）
-- =====================================================================

USE `es_rule`;

-- ---------------------------------------------------------------------
-- 1. 场景联动规则（TCA 模型：Trigger/Condition/Action 以 JSON 承载）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `iot_scene_rule`;
CREATE TABLE `iot_scene_rule` (
  `rule_id`         BIGINT       NOT NULL AUTO_INCREMENT,
  `tenant_id`       BIGINT       NOT NULL,
  `rule_code`       VARCHAR(64)  NOT NULL COMMENT '规则编码，如 SCENE_TEMP_HIGH',
  `rule_name`       VARCHAR(128) NOT NULL,
  `description`     VARCHAR(512)          DEFAULT NULL,
  `dsl_version`     INT          NOT NULL DEFAULT 1 COMMENT 'DSL 版本，升级不破坏旧规则',
  `trigger_json`    JSON         NOT NULL COMMENT 'triggers[]：PROPERTY/TIMER/LIFECYCLE/ALARM/MANUAL',
  `condition_json`  JSON         NOT NULL COMMENT 'conditions[]：DEVICE_STATUS/TIME_RANGE/PROPERTY，可为空数组',
  `action_json`     JSON         NOT NULL COMMENT 'actions[]：DEVICE_COMMAND/ALARM/NOTIFY/RULE',
  `recovery_json`   JSON                  DEFAULT NULL COMMENT '恢复配置：条件从满足→不满足时执行的恢复动作',
  `debounce_seconds` INT         NOT NULL DEFAULT 300 COMMENT '动作防抖窗口（秒）',
  `priority`        INT          NOT NULL DEFAULT 100 COMMENT '同一事件多规则命中时的执行优先级（小优先）',
  `enabled`         TINYINT      NOT NULL DEFAULT 1 COMMENT '0停用 1启用',
  `version`         INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  `create_by`       BIGINT                DEFAULT NULL,
  `create_time`     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`rule_id`),
  UNIQUE KEY `uk_tenant_code` (`tenant_id`, `rule_code`),
  KEY `idx_tenant_enabled` (`tenant_id`, `enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='场景联动规则（TCA）';

-- ---------------------------------------------------------------------
-- 2. 规则执行日志（按月分区，同步归档 ES）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `iot_scene_exec_log`;
CREATE TABLE `iot_scene_exec_log` (
  `log_id`        BIGINT      NOT NULL,
  `rule_id`       BIGINT      NOT NULL,
  `rule_code`     VARCHAR(64) NOT NULL COMMENT '冗余编码，便于检索',
  `tenant_id`     BIGINT      NOT NULL,
  `trigger_type`  VARCHAR(32) NOT NULL COMMENT 'PROPERTY/TIMER/LIFECYCLE/ALARM/MANUAL/RULE',
  `device_id`     BIGINT               DEFAULT NULL COMMENT '触发设备（可空）',
  `matched`       TINYINT     NOT NULL DEFAULT 0 COMMENT '1=条件满足执行 0=触发未过条件',
  `action_result` JSON                 DEFAULT NULL COMMENT '每个动作的执行结果（成功/失败/错误信息）',
  `cost_ms`       INT         NOT NULL DEFAULT 0 COMMENT '引擎处理耗时（毫秒）',
  `trace_id`      VARCHAR(64)          DEFAULT NULL COMMENT '链路追踪 ID',
  `create_time`   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`log_id`, `create_time`),
  KEY `idx_log_rule_time` (`rule_id`, `create_time`),
  KEY `idx_log_tenant_time` (`tenant_id`, `create_time`),
  KEY `idx_log_device_time` (`device_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='场景联动规则执行日志'
PARTITION BY RANGE COLUMNS(`create_time`) (
  PARTITION p202607 VALUES LESS THAN ('2026-08-01'),
  PARTITION p202608 VALUES LESS THAN ('2026-09-01'),
  PARTITION p202609 VALUES LESS THAN ('2026-10-01'),
  PARTITION pMax   VALUES LESS THAN (MAXVALUE)
);
