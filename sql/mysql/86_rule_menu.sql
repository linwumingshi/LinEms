-- =====================================================================
-- EnergyX 储能管理平台 · 手工部署脚本（非 Flyway 环境，与 85_rule.sql 平行）
-- 86_rule_menu.sql：场景联动（规则编排）菜单资源
--  1) 新增「场景联动」菜单（perm_id=720，挂设备运维 700 下，与告警中心同级）
--  2) 超级管理员(role 1) 关联全部资源（幂等）
-- 说明：全部幂等（INSERT IGNORE / SELECT 授权），可安全重复执行。
-- 对应前端路由：/rule（Rule.vue，Phase 11 场景联动页面）
-- =====================================================================

-- 1. 场景联动菜单（visible=0 纯导航，status=0 正常；icon 对齐前端 Connection 图标）
INSERT IGNORE INTO `es_system`.`sys_permission`
  (`perm_id`, `parent_id`, `perm_code`, `perm_name`, `perm_type`, `resource_type`, `path`, `sort`, `icon`, `component`, `visible`, `status`, `remark`)
VALUES
  (720, 700, 'rule:view', '场景联动', 1, 'STRATEGY', 'rule', 6, 'connection', 'rule/index', 0, 0, '场景联动菜单（规则编排，Phase 11）');

-- 2. 超级管理员（role 1）关联全部资源（含新增菜单）
INSERT IGNORE INTO `es_system`.`sys_role_permission` (`role_id`, `perm_id`)
  SELECT 1, `perm_id` FROM `es_system`.`sys_permission`;
