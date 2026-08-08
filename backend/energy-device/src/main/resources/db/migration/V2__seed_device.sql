-- =====================================================================
-- EnergyX 储能管理平台 · device 域（energy-device 服务）
-- Flyway V2：默认 PCS 设备种子（与 stress.jar seed 派生规则完全一致）
--   device_id  = 8000000000000000000 + index（seed 专用号段，避开雪花）
--   device_name= sim-dev-%06d（index=1 → sim-dev-000001）
--   device_secret = hex(SHA-256("sanduo-stress:index"))
--   status=2 已激活(离线)，满足 Broker「仅 2/3 允许接入」校验
-- 自愈设计：ON DUPLICATE KEY UPDATE 而非 INSERT IGNORE——
--   设备逻辑删除（deleted=1）后重跑本脚本，会把设备行复活（deleted=0/status=2）
--   并把被吊销凭据重新激活（auth_status=1），避免「删 A 重加 A 连不上」。
--   重复执行幂等：对已就绪数据是零改动更新。
-- =====================================================================
INSERT INTO `iot_device`
  (`device_id`, `tenant_id`, `enterprise_id`, `station_id`, `product_key`, `device_name`,
   `device_type`, `parent_id`, `path`, `level`, `sort`, `status`, `protocol`, `deleted`)
VALUES
  (8000000000000000001, 1, NULL, NULL, 'snd_ess_pcs', 'sim-dev-000001',
   'PCS', 0, '/', 1, 0, 2, 'MQTT', 0)
ON DUPLICATE KEY UPDATE
  `deleted` = 0, `status` = 2;

INSERT INTO `iot_device_credential`
  (`device_id`, `tenant_id`, `device_secret`, `auth_status`, `fail_count`)
VALUES
  (8000000000000000001, 1,
   '9caa57c569a043634acc87435bf508a8adfb8035cb83e3d5368af1c3ecc4a99e', 1, 0)
ON DUPLICATE KEY UPDATE
  `device_secret` = VALUES(`device_secret`), `auth_status` = 1, `fail_count` = 0;
