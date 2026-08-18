-- =====================================================================
-- EnergyX 储能管理平台 · device 域（energy-device 服务）
-- Flyway V3：设备用户自定义显示名
-- 语义：device_name 是设备 code（平台内唯一标识/接入协议锚点，创建后不可改）；
--       display_name 仅管理端展示用，可空可改可重复，为空时前端回退显示设备 code。
-- 说明：V1 用 IF NOT EXISTS 兼容种子库/全新库；本版本为增量 ALTER，
--       Flyway 依据 schema_history 仅执行一次，无需幂等判断。
-- =====================================================================

ALTER TABLE `iot_device`
  ADD COLUMN `display_name` VARCHAR(128) DEFAULT NULL COMMENT '用户自定义显示名（可空，仅展示）' AFTER `device_name`;
