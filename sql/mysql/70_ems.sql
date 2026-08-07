-- =====================================================================
-- EnergyX 储能管理平台 · ems 域（energy-ems 服务）
-- 70_ems.sql —— 策略定义 / 计划头 / 电价 / 约束 / 执行记录
-- 版本：v1.0    日期：2026-08-06
-- 设计依据：Phase1 §7.1 ems-plan topic；Phase1 §2.4 控制分级闭环（安全包络）
-- 说明：策略输出的充放电点序列不落 MySQL（高频时序走 TDengine ems_plan_point），
--       本域仅存计划头与低频配置。
-- =====================================================================

USE `es_ems`;

-- ---------------------------------------------------------------------
-- 1. 策略定义（峰谷套利 / 需量 / 需求响应 / SOC 约束 / 时间策略）
--    config 示例（峰谷套利）：
--      {"chargeWindows":[{"start":"02:00","end":"06:00","powerLimit":100}],
--       "dischargeWindows":[{"start":"18:00","end":"22:00","powerLimit":100}],
--       "socRange":{"min":10,"max":90}}
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `ems_strategy`;
CREATE TABLE `ems_strategy` (
  `strategy_id`   BIGINT       NOT NULL AUTO_INCREMENT,
  `tenant_id`     BIGINT       NOT NULL,
  `station_id`    BIGINT       NOT NULL COMMENT '作用电站',
  `strategy_name` VARCHAR(128) NOT NULL,
  `strategy_type` VARCHAR(32)  NOT NULL COMMENT 'PEAK_VALLEY/DEMAND/DR/SOC_CTRL/TIME',
  `config`        JSON         NOT NULL COMMENT '策略配置',
  `priority`      INT          NOT NULL DEFAULT 0 COMMENT '优先级（多策略冲突仲裁）',
  `status`        TINYINT      NOT NULL DEFAULT 0 COMMENT '0草稿 1启用 2停用',
  `version`       INT          NOT NULL DEFAULT 1 COMMENT '策略版本',
  `create_by`     BIGINT                DEFAULT NULL,
  `create_time`   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`strategy_id`),
  KEY `idx_strategy_station` (`station_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='储能策略定义';

-- ---------------------------------------------------------------------
-- 2. 策略计划头（充放电点序列入 TDengine，此处存计划元数据）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `ems_plan`;
CREATE TABLE `ems_plan` (
  `plan_id`      BIGINT      NOT NULL AUTO_INCREMENT,
  `tenant_id`    BIGINT      NOT NULL,
  `station_id`   BIGINT      NOT NULL,
  `strategy_id`  BIGINT      NOT NULL,
  `plan_date`    DATE        NOT NULL COMMENT '计划日期',
  `plan_type`    TINYINT     NOT NULL COMMENT '1充电 2放电 3混合',
  `total_energy` DECIMAL(12,3) DEFAULT NULL COMMENT '计划总量 kWh',
  `plan_param`   JSON                 DEFAULT NULL COMMENT '计划参数快照',
  `status`       TINYINT     NOT NULL DEFAULT 0 COMMENT '0待执行 1执行中 2完成 3已取消',
  `create_time`  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`plan_id`),
  KEY `idx_plan_station_date` (`station_id`, `plan_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='储能策略计划';

-- ---------------------------------------------------------------------
-- 3. 分时电价（尖/峰/平/谷/深谷）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `ems_electricity_price`;
CREATE TABLE `ems_electricity_price` (
  `price_id`    BIGINT       NOT NULL AUTO_INCREMENT,
  `tenant_id`   BIGINT       NOT NULL,
  `station_id`  BIGINT       NOT NULL,
  `region`      VARCHAR(32)  NOT NULL DEFAULT 'DEFAULT' COMMENT '区域',
  `price_type`  VARCHAR(16)  NOT NULL COMMENT 'DEEP/PEEK/PEAK/FLAT/VALLEY',
  `start_time`  TIME         NOT NULL,
  `end_time`    TIME         NOT NULL,
  `price`       DECIMAL(18,4) NOT NULL COMMENT '电价 元/kWh',
  `valid_from`  DATE         NOT NULL,
  `valid_to`    DATE         NOT NULL,
  `status`      TINYINT      NOT NULL DEFAULT 1,
  `create_time` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`price_id`),
  KEY `idx_price_station` (`station_id`, `valid_from`, `valid_to`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分时电价';

-- ---------------------------------------------------------------------
-- 4. 安全约束（下发前安全包络校验，Phase1 §2.4）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `ems_constraint`;
CREATE TABLE `ems_constraint` (
  `constraint_id`      BIGINT        NOT NULL AUTO_INCREMENT,
  `tenant_id`          BIGINT        NOT NULL,
  `station_id`         BIGINT        NOT NULL,
  `soc_min`            DECIMAL(5,2)  NOT NULL DEFAULT 10 COMMENT 'SOC下限 %',
  `soc_max`            DECIMAL(5,2)  NOT NULL DEFAULT 90 COMMENT 'SOC上限 %',
  `charge_power_max`   DECIMAL(12,3) NOT NULL DEFAULT 0 COMMENT '最大充电功率 kW',
  `discharge_power_max` DECIMAL(12,3) NOT NULL DEFAULT 0 COMMENT '最大放电功率 kW',
  `temp_max`           DECIMAL(5,2)           DEFAULT NULL COMMENT '温度上限 ℃',
  `voltage_max`        DECIMAL(10,2)          DEFAULT NULL,
  `current_max`        DECIMAL(10,2)          DEFAULT NULL,
  `safety_envelope`    JSON                  DEFAULT NULL COMMENT '扩展安全包络',
  `status`             TINYINT      NOT NULL DEFAULT 1,
  `create_time`        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`constraint_id`),
  UNIQUE KEY `uk_constraint_station` (`station_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='安全约束（下发包络校验）';

-- ---------------------------------------------------------------------
-- 5. 策略执行记录（审计）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `ems_execution_record`;
CREATE TABLE `ems_execution_record` (
  `exec_id`      BIGINT      NOT NULL AUTO_INCREMENT,
  `tenant_id`    BIGINT      NOT NULL,
  `plan_id`      BIGINT      NOT NULL,
  `command_id`   VARCHAR(64) NOT NULL COMMENT '对应 iot_command',
  `device_id`    BIGINT      NOT NULL,
  `action`       VARCHAR(32) NOT NULL COMMENT 'CHARGE/DISCHARGE/STANDBY',
  `params`       JSON                 DEFAULT NULL,
  `result`       JSON                 DEFAULT NULL COMMENT '执行回执',
  `execute_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`exec_id`),
  KEY `idx_exec_plan` (`plan_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='策略执行记录';
