-- =====================================================================
-- EnergyX 储能管理平台 · 手工部署脚本（非 Flyway 环境，与 87_alarm_rule_seed.sql 平行）
-- 89_notify_menu.sql：消息通知菜单资源（energy-notify 服务配套）
--  1) 新增「消息通知」一级目录（perm_id=730）+ 通知配置(731)/通知模板(732)
--  2) 超级管理员(role 1) 关联全部资源（幂等）
-- 说明：全部幂等（INSERT IGNORE / SELECT 授权），可安全重复执行。
-- =====================================================================

-- 1. 消息通知目录与子菜单
INSERT IGNORE INTO `es_system`.`sys_permission`
  (`perm_id`, `parent_id`, `perm_code`, `perm_name`, `perm_type`, `resource_type`, `path`, `sort`, `icon`, `component`, `visible`, `status`, `remark`)
VALUES
  (730, 0,   'notify:group',        '消息通知', 1, NULL,     'notify',         12, 'message',        NULL,                  0, 0, '消息通知目录（energy-notify）'),
  (731, 730, 'notify:config:view',  '通知配置', 1, NULL,     'notify/config',  1,  'message',        'notify/config/index',  0, 0, '通知配置菜单'),
  (732, 730, 'notify:template:view','通知模板', 1, NULL,     'notify/template',2,  'document',       'notify/template/index',0, 0, '通知模板菜单');

-- 2. 超级管理员（role 1）关联全部资源（含新增菜单）
INSERT IGNORE INTO `es_system`.`sys_role_permission` (`role_id`, `perm_id`)
  SELECT 1, `perm_id` FROM `es_system`.`sys_permission`;
