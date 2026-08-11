-- =====================================================================
-- EnergyX 储能管理平台 · 产品域（energy-product 服务）
-- V2__seed_meter.sql —— 进线电能表产品种子（P1-2 需量管理功率数据源）
-- 版本：v1.5    日期：2026-08-11
-- 说明：新增 device_type='METER' 的 snd_ess_meter 产品，物模型属性 importPower（进线功率 kW）。
--       V1 已应用过（INSERT IGNORE 对已存在库不会再执行），故单独新迁移。
-- =====================================================================

INSERT IGNORE INTO `iot_product` (`product_id`, `tenant_id`, `category_id`, `product_key`, `product_name`, `device_type`, `auth_type`, `model_version`, `status`) VALUES
(2, 1, NULL, 'snd_ess_meter', '进线电能表', 'METER', 'SECRET', 'V1.0', 1);

INSERT IGNORE INTO `iot_thing_model` (`model_id`, `tenant_id`, `product_id`, `version`, `schema_json`, `status`, `is_current`) VALUES
(2, 1, 2, 'V1.0',
 '{"properties":[{"identifier":"importPower","name":"进线功率","dataType":"float","unit":"kW","accessMode":"r"}],"services":[],"events":[]}',
 1, 1);

INSERT IGNORE INTO `iot_thing_model_identifier` (`tenant_id`, `product_id`, `model_version`, `identifier`, `identifier_type`, `data_type`, `unit`, `required`) VALUES
(1, 2, 'V1.0', 'importPower', 1, 'float', 'kW', 1);
