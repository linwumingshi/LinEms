-- EnergyX 品牌统一：既有库（已执行 V1~V3）中旧品牌种子数据 →「EnergyX / ENX」。
-- 全新库的 V1 种子数据已直接使用新品牌，V4 的 UPDATE 命中同值幂等，可安全重放。
UPDATE `sys_tenant` SET `tenant_code` = 'ENX', `tenant_name` = 'EnergyX'
WHERE `tenant_id` = 1 AND `tenant_code` = 'SND';

UPDATE `sys_enterprise` SET `enterprise_code` = 'ENX-HQ', `enterprise_name` = 'EnergyX 集团本部'
WHERE `enterprise_id` = 1 AND `enterprise_code` = 'SND-HQ';
