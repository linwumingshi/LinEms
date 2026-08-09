-- =====================================================================
-- EnergyX 储能管理平台 · system 域（energy-system 服务）
-- Flyway V5：菜单权限数据完善
--  1) 八大业务一级菜单入表（设备监控/影子/指令中心/告警中心/产品管理/设备管理/策略管理/充放电计划）
--  2) 新增 基础档案目录 + 电站管理（含按钮）；单位管理(400) 移出系统管理、挂到基础档案下
--  3) 系统管理目录排到业务菜单之后
--  4) 超级管理员(role 1) 关联全部资源
--  5) 清理历史半选父节点误存（getHalfCheckedKeys 落库遗留：role 11 → perm 1）
-- 说明：全部幂等（INSERT IGNORE / 定点 UPDATE / 定点 DELETE），可安全重复执行。
-- =====================================================================

-- 1. 八大业务一级菜单（visible=0 纯导航，status=0 正常）
INSERT IGNORE INTO `sys_permission`
  (`perm_id`, `parent_id`, `perm_code`, `perm_name`, `perm_type`, `resource_type`, `path`, `sort`, `icon`, `component`, `visible`, `status`, `remark`)
VALUES
  (600, 0, 'monitor:view',   '设备监控',   1, 'DEVICE',   'dashboard',     2, 'monitor',    'dashboard/index',     0, 0, '设备监控菜单'),
  (610, 0, 'shadow:view',    '影子',       1, 'DEVICE',   'shadow',        3, 'connection', 'shadow/index',        0, 0, '影子菜单'),
  (620, 0, 'command:view',   '指令中心',   1, 'DEVICE',   'command',       4, 'promotion',  'command/index',       0, 0, '指令中心菜单'),
  (630, 0, 'alarm:view',     '告警中心',   1, 'ALARM',    'alarm',         5, 'bell',       'alarm/index',         0, 0, '告警中心菜单'),
  (640, 0, 'product:view',   '产品管理',   1, 'DEVICE',   'product',       6, 'goods',      'product/index',       0, 0, '产品管理菜单'),
  (650, 0, 'device:view',    '设备管理',   1, 'DEVICE',   'device',        7, 'cpu',        'device/index',        0, 0, '设备管理菜单'),
  (660, 0, 'strategy:view',  '策略管理',   1, 'STRATEGY', 'ems/strategy',  8, 'operation',  'ems/strategy/index',  0, 0, '策略管理菜单'),
  (670, 0, 'plan:view',      '充放电计划', 1, 'STRATEGY', 'ems/plan',      9, 'date-range', 'ems/plan/index',      0, 0, '充放电计划菜单');

-- 2. 基础档案目录 + 电站管理（含按钮）
INSERT IGNORE INTO `sys_permission`
  (`perm_id`, `parent_id`, `perm_code`, `perm_name`, `perm_type`, `resource_type`, `path`, `sort`, `icon`, `component`, `visible`, `status`, `remark`)
VALUES
  (2,    0,   'archive',                 '基础档案', 1, NULL,     'archive',         10, 'office-building', NULL,                      0, 0, '基础档案目录'),
  (500,  2,   'system:station:list',     '电站管理', 1, 'STATION', 'archive/station',  2, 'location',       'archive/station/index', 0, 0, '电站管理菜单'),
  (501,  500, 'system:station:add',      '电站新增', 2, NULL,     NULL, 1, NULL, NULL, 1, 0, NULL),
  (502,  500, 'system:station:edit',     '电站编辑', 2, NULL,     NULL, 2, NULL, NULL, 1, 0, NULL),
  (503,  500, 'system:station:remove',   '电站删除', 2, NULL,     NULL, 3, NULL, NULL, 1, 0, NULL);

-- 3. 单位管理(400) 移出系统管理：父级 → 2（基础档案），路由/组件挂 archive
UPDATE `sys_permission` SET `parent_id` = 2, `path` = 'archive/enterprise', `component` = 'archive/enterprise/index', `sort` = 1 WHERE `perm_id` = 400;

-- 4. 系统管理目录排到业务菜单之后
UPDATE `sys_permission` SET `sort` = 11 WHERE `perm_id` = 1;

-- 5. 超级管理员（role 1）关联全部资源
INSERT IGNORE INTO `sys_role_permission` (`role_id`, `perm_id`)
  SELECT 1, `perm_id` FROM `sys_permission`;

-- 6. 清理半选父节点误存：role 11 仅授权 角色管理/单位管理 子树，却存了目录 id=1（重开授权 setCheckedKeys 会级联全选其全部子孙）
DELETE FROM `sys_role_permission` WHERE `role_id` = 11 AND `perm_id` = 1;
