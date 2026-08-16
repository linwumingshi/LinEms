-- =====================================================================
-- EnergyX 储能管理平台 · OTA 固件升级菜单资源（energy-ota 服务配套）
-- V9__menu_ota_seed.sql
--  1) 新增「升级包管理」(perm_id=740) 与「升级任务」(perm_id=741)，
--     挂设备运维 700 下（场景联动 720 之后，sort=7/8）
--  2) 超级管理员(role 1) 关联全部资源（幂等）
-- 说明：全部幂等（INSERT IGNORE / SELECT 授权），可安全重复执行。
-- 对应前端路由：/ota/package（OtaPackage.vue）、/ota/task（OtaTask.vue）
-- =====================================================================

-- 1. OTA 菜单
INSERT IGNORE INTO `sys_permission`
  (`perm_id`, `parent_id`, `perm_code`, `perm_name`, `perm_type`, `resource_type`, `path`, `sort`, `icon`, `component`, `visible`, `status`, `remark`)
VALUES
  (740, 700, 'ota:package:view', '升级包管理', 1, 'OTA', 'ota/package', 7, 'upload-filled', 'ota/package/index', 0, 0, 'OTA 升级包管理（全量/差分，energy-ota）'),
  (741, 700, 'ota:task:view',   '升级任务',   1, 'OTA', 'ota/task',    8, 'promotion',     'ota/task/index',    0, 0, 'OTA 批次升级任务（灰度/重试/超时）');

-- 2. 超级管理员（role 1）关联全部资源（含新增菜单）
INSERT IGNORE INTO `sys_role_permission` (`role_id`, `perm_id`)
  SELECT 1, `perm_id` FROM `sys_permission`;
