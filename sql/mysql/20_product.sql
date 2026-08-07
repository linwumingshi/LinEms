-- =====================================================================
-- EnergyX 储能管理平台 · product 域（energy-product 服务）
-- 20_product.sql —— 产品品类 / 产品 / 物模型（JSON Schema + 标识符投影）
-- 版本：v1.0    日期：2026-08-06
-- 设计依据：ADR-008（物模型规范）；决策 A.3.3（JSON 整行快照 + identifier 投影表）
-- 路由键：tenant_id（读多写少，Redis 缓存）
-- =====================================================================

USE `es_product`;

-- ---------------------------------------------------------------------
-- 1. 产品品类（用于管理端分组，不参与运行时）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `iot_product_category`;
CREATE TABLE `iot_product_category` (
  `category_id`   BIGINT      NOT NULL AUTO_INCREMENT,
  `tenant_id`     BIGINT      NOT NULL,
  `parent_id`     BIGINT      NOT NULL DEFAULT 0,
  `category_name` VARCHAR(64) NOT NULL,
  `sort`          INT         NOT NULL DEFAULT 0,
  `status`        TINYINT     NOT NULL DEFAULT 1,
  `create_time`   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`category_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='产品品类';

-- ---------------------------------------------------------------------
-- 2. 产品（设备接入与物模型命名的锚点）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `iot_product`;
CREATE TABLE `iot_product` (
  `product_id`   BIGINT       NOT NULL AUTO_INCREMENT,
  `tenant_id`    BIGINT       NOT NULL,
  `category_id`  BIGINT                DEFAULT NULL,
  `product_key`  VARCHAR(64)  NOT NULL COMMENT '产品标识，如 snd_ess_pcs（全局路由锚点）',
  `product_name` VARCHAR(128) NOT NULL,
  `device_type`  VARCHAR(32)  NOT NULL COMMENT 'ENERGY_CABINET/BATTERY_CLUSTER/PCS/BMS/EMS/EDGE_GW',
  `auth_type`    VARCHAR(16)  NOT NULL DEFAULT 'SECRET' COMMENT 'SECRET：设备密钥  CERT：证书',
  `protocol`     VARCHAR(16)  NOT NULL DEFAULT 'MQTT' COMMENT '接入协议',
  `model_version` VARCHAR(32)          DEFAULT NULL COMMENT '当前生效物模型版本',
  `description`  VARCHAR(512)          DEFAULT NULL,
  `status`       TINYINT      NOT NULL DEFAULT 1 COMMENT '0禁用 1启用',
  `create_time`  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `deleted`      TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (`product_id`),
  UNIQUE KEY `uk_product_key` (`tenant_id`, `product_key`),
  KEY `idx_product_type` (`device_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='产品';

-- ---------------------------------------------------------------------
-- 3. 物模型（属性/服务/事件 JSON Schema，整行版本快照）
--    schema_json 结构（对齐阿里云物模型）：
--    {
--      "properties": [ { "identifier":"soc","name":"荷电状态","dataType":"float",
--                        "unit":"%","accessMode":"rw","required":true } ... ],
--      "services":   [ { "identifier":"startCharge","name":"启动充电",
--                        "input":[ {...} ], "output":[ {...} ] } ... ],
--      "events":     [ { "identifier":"overTemp","name":"过温告警",
--                        "type":"INFO|WARN|ERROR","data":[...] } ... ]
--    }
--    运行时由接入适配服务缓存解析校验，见 Redis-key规范 cache:model:*。
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `iot_thing_model`;
CREATE TABLE `iot_thing_model` (
  `model_id`    BIGINT       NOT NULL AUTO_INCREMENT,
  `tenant_id`   BIGINT       NOT NULL,
  `product_id`  BIGINT       NOT NULL,
  `version`     VARCHAR(32)  NOT NULL COMMENT '物模型版本，如 V1.0',
  `schema_json` JSON         NOT NULL COMMENT '完整物模型 JSON Schema（整行快照）',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0草稿 1已发布 2已废弃',
  `is_current`  TINYINT      NOT NULL DEFAULT 0 COMMENT '当前生效版本',
  `create_by`   BIGINT                DEFAULT NULL,
  `create_time` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`model_id`),
  UNIQUE KEY `uk_model_version` (`product_id`, `version`),
  KEY `idx_model_current` (`product_id`, `is_current`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='物模型版本';

-- ---------------------------------------------------------------------
-- 4. 物模型标识符投影表（降级索引，非物模型本体）
--    用途：① TDengine STABLE 列映射 ② identifier 检索 ③ 上报校验白名单
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `iot_thing_model_identifier`;
CREATE TABLE `iot_thing_model_identifier` (
  `id`          BIGINT      NOT NULL AUTO_INCREMENT,
  `tenant_id`   BIGINT      NOT NULL,
  `product_id`  BIGINT      NOT NULL,
  `model_version` VARCHAR(32) NOT NULL COMMENT '所属版本',
  `identifier`  VARCHAR(64) NOT NULL COMMENT '标识符，如 soc/voltage/startCharge/overTemp',
  `identifier_type` TINYINT  NOT NULL COMMENT '1属性 2服务 3事件',
  `data_type`   VARCHAR(32) NOT NULL COMMENT 'float/int/bool/enum/string/struct',
  `unit`        VARCHAR(32)          DEFAULT NULL,
  `is_ext`      TINYINT     NOT NULL DEFAULT 0 COMMENT '是否扩展属性',
  `required`    TINYINT     NOT NULL DEFAULT 0,
  `description` VARCHAR(256)         DEFAULT NULL,
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_identifier` (`product_id`, `model_version`, `identifier`),
  KEY `idx_identifier_type` (`product_id`, `identifier_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='物模型标识符投影';

-- =====================================================================
-- 种子数据：储能 PCS 产品示例（完整物模型）
-- =====================================================================
INSERT INTO `iot_product` (`product_id`, `tenant_id`, `category_id`, `product_key`, `product_name`, `device_type`, `auth_type`, `model_version`, `status`) VALUES
(1, 1, NULL, 'snd_ess_pcs', '储能变流器 PCS', 'PCS', 'SECRET', 'V1.0', 1);

INSERT INTO `iot_thing_model` (`model_id`, `tenant_id`, `product_id`, `version`, `schema_json`, `status`, `is_current`) VALUES
(1, 1, 1, 'V1.0',
 '{"properties":[{"identifier":"soc","name":"荷电状态","dataType":"float","unit":"%","accessMode":"r"},{"identifier":"voltage","name":"母线电压","dataType":"float","unit":"V","accessMode":"r"},{"identifier":"current","name":"母线电流","dataType":"float","unit":"A","accessMode":"r"},{"identifier":"power","name":"功率","dataType":"float","unit":"kW","accessMode":"r"},{"identifier":"temp","name":"温度","dataType":"float","unit":"℃","accessMode":"r"},{"identifier":"runMode","name":"运行模式","dataType":"enum","enumValues":[{"value":0,"desc":"待机"},{"value":1,"desc":"充电"},{"value":2,"desc":"放电"}],"accessMode":"rw"}],"services":[{"identifier":"startCharge","name":"启动充电","input":[{"identifier":"power","name":"充电功率","dataType":"float","unit":"kW"}],"output":[]},{"identifier":"stopCharge","name":"停止充电","input":[],"output":[]},{"identifier":"setPower","name":"调整功率","input":[{"identifier":"power","name":"目标功率","dataType":"float","unit":"kW"}],"output":[]},{"identifier":"setRunMode","name":"设置运行模式","input":[{"identifier":"mode","name":"模式","dataType":"enum"}],"output":[]}],"events":[{"identifier":"overTemp","name":"过温告警","type":"WARN","data":[{"identifier":"temp","dataType":"float"}]},{"identifier":"overVoltage","name":"过压告警","type":"WARN","data":[{"identifier":"voltage","dataType":"float"}]},{"identifier":"bmsFault","name":"BMS故障","type":"ERROR","data":[]}]}',
 1, 1);

INSERT INTO `iot_thing_model_identifier` (`tenant_id`, `product_id`, `model_version`, `identifier`, `identifier_type`, `data_type`, `unit`, `required`) VALUES
(1, 1, 'V1.0', 'soc',       1, 'float',  '%', 1),
(1, 1, 'V1.0', 'voltage',   1, 'float',  'V',  1),
(1, 1, 'V1.0', 'current',   1, 'float',  'A',  1),
(1, 1, 'V1.0', 'power',     1, 'float',  'kW', 1),
(1, 1, 'V1.0', 'temp',      1, 'float',  '℃', 1),
(1, 1, 'V1.0', 'runMode',   1, 'enum',   NULL, 0),
(1, 1, 'V1.0', 'startCharge', 2, 'void',  NULL, 0),
(1, 1, 'V1.0', 'stopCharge',  2, 'void',  NULL, 0),
(1, 1, 'V1.0', 'setPower',    2, 'void',  NULL, 0),
(1, 1, 'V1.0', 'setRunMode',  2, 'void',  NULL, 0),
(1, 1, 'V1.0', 'overTemp',    3, 'struct', NULL, 0),
(1, 1, 'V1.0', 'overVoltage', 3, 'struct', NULL, 0),
(1, 1, 'V1.0', 'bmsFault',    3, 'struct', NULL, 0);
