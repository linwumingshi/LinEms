-- =====================================================================
-- EnergyX 储能管理平台 · system 域（energy-system 服务）
-- 10_sys.sql —— 租户 / 企业组织树 / RBAC / 操作审计
-- 版本：v1.0    日期：2026-08-06
-- 路由键：tenant_id（行级隔离，单逻辑库）
-- =====================================================================

USE `es_system`;

-- ---------------------------------------------------------------------
-- 1. 租户（=集团）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `sys_tenant`;
CREATE TABLE `sys_tenant` (
  `tenant_id`    BIGINT       NOT NULL AUTO_INCREMENT COMMENT '租户ID（集团）',
  `tenant_code`  VARCHAR(64)  NOT NULL COMMENT '租户编码',
  `tenant_name`  VARCHAR(128) NOT NULL COMMENT '租户名称（集团名）',
  `contact`      VARCHAR(64)           DEFAULT NULL COMMENT '联系人',
  `phone`        VARCHAR(32)           DEFAULT NULL COMMENT '联系电话',
  `quota`        JSON                  DEFAULT NULL COMMENT '资源配额（设备数/接入速率）',
  `status`       TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0禁用 1启用',
  `create_time`  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_time`  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted`      TINYINT      NOT NULL DEFAULT 0 COMMENT '软删：0正常 1删除',
  PRIMARY KEY (`tenant_id`),
  UNIQUE KEY `uk_tenant_code` (`tenant_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户（集团）';

-- ---------------------------------------------------------------------
-- 2. 企业组织树（集团→企业→子企业，邻接表 + 物化路径）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `sys_enterprise`;
CREATE TABLE `sys_enterprise` (
  `enterprise_id`   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '企业ID',
  `tenant_id`       BIGINT       NOT NULL COMMENT '所属租户（集团）',
  `parent_id`       BIGINT       NOT NULL DEFAULT 0 COMMENT '父企业ID（0=顶级）',
  `path`            VARCHAR(512) NOT NULL DEFAULT '/' COMMENT '物化路径，如 /1/3/ 便于子树查询',
  `level`           TINYINT      NOT NULL DEFAULT 1 COMMENT '层级：1集团直属 2子企业',
  `enterprise_code` VARCHAR(64)  NOT NULL COMMENT '企业编码',
  `enterprise_name` VARCHAR(128) NOT NULL COMMENT '企业名称',
  `sort`            INT          NOT NULL DEFAULT 0 COMMENT '排序',
  `status`          TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0禁用 1启用',
  `create_time`     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `deleted`         TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (`enterprise_id`),
  UNIQUE KEY `uk_ent_code` (`tenant_id`, `enterprise_code`),
  KEY `idx_ent_parent` (`parent_id`),
  KEY `idx_ent_path` (`path`(191))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='企业组织树';

-- ---------------------------------------------------------------------
-- 3. 用户
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `sys_user`;
CREATE TABLE `sys_user` (
  `user_id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  `tenant_id`        BIGINT       NOT NULL COMMENT '租户ID',
  `enterprise_id`    BIGINT                DEFAULT NULL COMMENT '所属企业',
  `username`         VARCHAR(64)  NOT NULL COMMENT '登录名',
  `password`         VARCHAR(128) NOT NULL COMMENT '密码（BCrypt）',
  `real_name`        VARCHAR(64)           DEFAULT NULL COMMENT '姓名',
  `phone`            VARCHAR(32)           DEFAULT NULL,
  `email`            VARCHAR(128)          DEFAULT NULL,
  `avatar`           VARCHAR(512)          DEFAULT NULL,
  `status`           TINYINT      NOT NULL DEFAULT 1 COMMENT '0禁用 1启用 2锁定',
  `last_login_time`  DATETIME(3)           DEFAULT NULL,
  `create_time`      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `deleted`          TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (`user_id`),
  UNIQUE KEY `uk_username` (`tenant_id`, `username`),
  KEY `idx_user_ent` (`enterprise_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户';

-- ---------------------------------------------------------------------
-- 4. RBAC
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `sys_role`;
CREATE TABLE `sys_role` (
  `role_id`     BIGINT       NOT NULL AUTO_INCREMENT,
  `tenant_id`   BIGINT       NOT NULL,
  `role_code`   VARCHAR(64)  NOT NULL COMMENT '角色编码',
  `role_name`   VARCHAR(64)  NOT NULL,
  `data_scope`  TINYINT      NOT NULL DEFAULT 1 COMMENT '数据范围：1本人 2本企业 3本租户 4全部',
  `status`      TINYINT      NOT NULL DEFAULT 1,
  `create_time` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`role_id`),
  UNIQUE KEY `uk_role_code` (`tenant_id`, `role_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色';

DROP TABLE IF EXISTS `sys_user_role`;
CREATE TABLE `sys_user_role` (
  `id`      BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL,
  `role_id` BIGINT NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_role` (`user_id`, `role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户-角色';

DROP TABLE IF EXISTS `sys_permission`;
CREATE TABLE `sys_permission` (
  `perm_id`       BIGINT      NOT NULL AUTO_INCREMENT,
  `parent_id`     BIGINT      NOT NULL DEFAULT 0,
  `perm_code`     VARCHAR(64) NOT NULL COMMENT '权限点编码，如 system:user:add',
  `perm_name`     VARCHAR(64) NOT NULL,
  `perm_type`     TINYINT     NOT NULL COMMENT '1菜单 2按钮 3数据',
  `resource_type` VARCHAR(32)          DEFAULT NULL COMMENT '资源类型：DEVICE/STRATEGY/ALARM/STATION',
  `path`          VARCHAR(256)         DEFAULT NULL COMMENT '前端路由',
  `sort`          INT         NOT NULL DEFAULT 0,
  `icon`          VARCHAR(100) NOT NULL DEFAULT '#' COMMENT '菜单图标',
  `component`     VARCHAR(255)          DEFAULT NULL COMMENT '前端组件路径',
  `visible`       TINYINT      NOT NULL DEFAULT 0 COMMENT '是否显示：0显示 1隐藏',
  `status`        TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0正常 1停用',
  `remark`        VARCHAR(500)          DEFAULT NULL COMMENT '备注',
  `create_time`   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`   DATETIME(3)           DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`perm_id`),
  UNIQUE KEY `uk_perm_code` (`perm_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='菜单资源';

DROP TABLE IF EXISTS `sys_role_permission`;
CREATE TABLE `sys_role_permission` (
  `id`      BIGINT NOT NULL AUTO_INCREMENT,
  `role_id` BIGINT NOT NULL,
  `perm_id` BIGINT NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_perm` (`role_id`, `perm_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色-权限';

-- ---------------------------------------------------------------------
-- 5. 操作审计（按月分区，保留 180 天，同步冗余至 ES es_operator_log）
--    MySQL 原生 RANGE 分区，分区由调度任务按月预建
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `sys_operator_log`;
CREATE TABLE `sys_operator_log` (
  `log_id`       BIGINT       NOT NULL COMMENT '日志ID（雪花）',
  `tenant_id`    BIGINT       NOT NULL,
  `operator_id`  BIGINT       NOT NULL COMMENT '操作人',
  `operator_name` VARCHAR(64)          DEFAULT NULL,
  `action`       VARCHAR(64)  NOT NULL COMMENT '操作，如 device:create',
  `target_type`  VARCHAR(32)           DEFAULT NULL COMMENT '对象类型：DEVICE/STRATEGY/...',
  `target_id`    VARCHAR(64)           DEFAULT NULL,
  `ip`           VARCHAR(64)           DEFAULT NULL,
  `user_agent`   VARCHAR(256)          DEFAULT NULL,
  `trace_id`     VARCHAR(64)           DEFAULT NULL COMMENT '全链路追踪ID',
  `detail`       JSON                  DEFAULT NULL COMMENT '变更明细',
  `create_time`  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`log_id`, `create_time`),
  KEY `idx_oplog_tenant_time` (`tenant_id`, `create_time`),
  KEY `idx_oplog_operator` (`operator_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作审计'
PARTITION BY RANGE COLUMNS(`create_time`) (
  PARTITION p202607 VALUES LESS THAN ('2026-08-01'),
  PARTITION p202608 VALUES LESS THAN ('2026-09-01'),
  PARTITION p202609 VALUES LESS THAN ('2026-10-01'),
  PARTITION pMax   VALUES LESS THAN (MAXVALUE)
);

-- =====================================================================
-- 种子数据
-- =====================================================================
INSERT INTO `sys_tenant` (`tenant_id`, `tenant_code`, `tenant_name`, `status`) VALUES
(1, 'ENX', 'EnergyX', 1);

INSERT INTO `sys_enterprise` (`enterprise_id`, `tenant_id`, `parent_id`, `path`, `level`, `enterprise_code`, `enterprise_name`) VALUES
(1, 1, 0, '/1/', 1, 'ENX-HQ', 'EnergyX 集团本部');

-- 超级管理员 / 初始角色（BCrypt，明文 admin123；与 energy-system Flyway V2 一致，生产部署后须改密）
INSERT INTO `sys_user` (`user_id`, `tenant_id`, `username`, `password`, `real_name`, `status`) VALUES
(1, 1, 'admin', '{bcrypt}$2a$10$F5EQNT8SSr7kgCqWA2SmKO0/KoRcWNR8BczVmdF3WrKZkS4bbifDK', '系统管理员', 1);

INSERT INTO `sys_role` (`role_id`, `tenant_id`, `role_code`, `role_name`, `data_scope`) VALUES
(1, 1, 'SUPER_ADMIN', '超级管理员', 4);

INSERT INTO `sys_user_role` (`user_id`, `role_id`) VALUES (1, 1);

-- 菜单资源种子（perm_type：1菜单 2按钮 3数据；perm_code 即 @ss.hasPermi 权限标识）
-- 一级菜单 sort：设备监控2 … 充放电计划9 · 基础档案10 · 系统管理11
INSERT IGNORE INTO `sys_permission`
  (`perm_id`, `parent_id`, `perm_code`, `perm_name`, `perm_type`, `resource_type`, `path`, `sort`, `icon`, `component`, `visible`, `status`, `remark`)
VALUES
  -- 八大业务一级菜单
  (690,  0,   'device:group',      '设备资产',   1, 'DEVICE',   'device',     6, 'cpu',        NULL,                           0, 0, '设备资产目录'),
  (700,  0,   'operation:group',   '设备运维',   1, 'DEVICE',   'operation',  3, 'monitor',    NULL,                           0, 0, '设备运维目录'),
  (710,  0,   'ems:group',         'EMS 能源管理', 1, 'STRATEGY', 'ems',        8, 'set-up',     NULL,                           0, 0, 'EMS 能源管理目录'),
  (600,  0,   'monitor:view',          '设备监控',   1, 'DEVICE',   'dashboard',     2, 'monitor',    'dashboard/index',     0, 0, '设备监控菜单'),
  (610,  700,  'shadow:view',           '影子',       1, 'DEVICE',   'shadow',        3, 'connection', 'shadow/index',        0, 0, '影子菜单'),
  (620,  700,  'command:view',          '指令中心',   1, 'DEVICE',   'command',       4, 'promotion',  'command/index',       0, 0, '指令中心菜单'),
  (630,  700,  'alarm:view',            '告警中心',   1, 'ALARM',    'alarm',         5, 'bell',       'alarm/index',         0, 0, '告警中心菜单'),
  (640,  690,  'product:view',          '产品管理',   1, 'DEVICE',   'product',       6, 'goods',      'product/index',       0, 0, '产品管理菜单'),
  (650,  690,  'device:view',           '设备管理',   1, 'DEVICE',   'device',        7, 'cpu',        'device/index',        0, 0, '设备管理菜单'),
  (660,  710,  'strategy:view',         '策略管理',   1, 'STRATEGY', 'ems/strategy',  8, 'operation',  'ems/strategy/index',  0, 0, '策略管理菜单'),
  (670,  710,  'plan:view',             '充放电计划', 1, 'STRATEGY', 'ems/plan',      9, 'date-range', 'ems/plan/index',      0, 0, '充放电计划菜单'),
  (680,  710,  'constraint:view',       '安全约束',   1, 'STRATEGY', 'ems/constraint', 10, 'lock', 'ems/constraint/index', 0, 0, '安全约束菜单'),
  -- 基础档案目录（单位管理 + 电站管理）
  (2,    0,   'archive',               '基础档案',   1, NULL,     'archive',           10, 'office-building', NULL,                      0, 0, '基础档案目录'),
  (400,  2,   'system:enterprise:list','单位管理',   1, NULL,     'archive/enterprise', 1, 'office-building', 'archive/enterprise/index', 0, 0, '单位管理菜单'),
  (500,  2,   'system:station:list',   '电站管理',   1, 'STATION', 'archive/station',    2, 'location',       'archive/station/index', 0, 0, '电站管理菜单'),
  -- 系统管理目录（排在业务菜单之后）
  (1,    0,   'system',                '系统管理',   1, NULL,     'system',            11, 'setting',         NULL,                      0, 0, '系统管理目录'),
  -- 用户管理
  (100,  1,   'system:user:list',      '用户管理', 1, NULL, 'system/user', 1, 'user', 'system/user/index', 0, 0, '用户管理菜单'),
  (101,  100, 'system:user:add',       '用户新增', 2, NULL, NULL, 1, NULL, NULL, 1, 0, NULL),
  (102,  100, 'system:user:edit',      '用户编辑', 2, NULL, NULL, 2, NULL, NULL, 1, 0, NULL),
  (103,  100, 'system:user:remove',    '用户删除', 2, NULL, NULL, 3, NULL, NULL, 1, 0, NULL),
  (104,  100, 'system:user:resetPwd',  '重置密码', 2, NULL, NULL, 4, NULL, NULL, 1, 0, NULL),
  (105,  100, 'system:user:role',      '分配角色', 2, NULL, NULL, 5, NULL, NULL, 1, 0, NULL),
  -- 角色管理
  (200,  1,   'system:role:list',      '角色管理', 1, NULL, 'system/role', 2, 'peoples', 'system/role/index', 0, 0, '角色管理菜单'),
  (201,  200, 'system:role:add',       '角色新增', 2, NULL, NULL, 1, NULL, NULL, 1, 0, NULL),
  (202,  200, 'system:role:edit',      '角色编辑', 2, NULL, NULL, 2, NULL, NULL, 1, 0, NULL),
  (203,  200, 'system:role:remove',    '角色删除', 2, NULL, NULL, 3, NULL, NULL, 1, 0, NULL),
  (204,  200, 'system:role:perm',      '分配权限', 2, NULL, NULL, 4, NULL, NULL, 1, 0, NULL),
  -- 菜单管理
  (300,  1,   'system:perm:list',      '菜单管理', 1, NULL, 'system/menu', 3, 'tree-table', 'system/menu/index', 0, 0, '菜单管理菜单'),
  (301,  300, 'system:perm:add',       '菜单新增', 2, NULL, NULL, 1, NULL, NULL, 1, 0, NULL),
  (302,  300, 'system:perm:edit',      '菜单编辑', 2, NULL, NULL, 2, NULL, NULL, 1, 0, NULL),
  (303,  300, 'system:perm:remove',    '菜单删除', 2, NULL, NULL, 3, NULL, NULL, 1, 0, NULL),
  -- 单位管理按钮
  (401,  400, 'system:enterprise:add', '单位新增', 2, NULL, NULL, 1, NULL, NULL, 1, 0, NULL),
  (402,  400, 'system:enterprise:edit','单位编辑', 2, NULL, NULL, 2, NULL, NULL, 1, 0, NULL),
  (403,  400, 'system:enterprise:remove', '单位删除', 2, NULL, NULL, 3, NULL, NULL, 1, 0, NULL),
  -- 电站管理按钮
  (501,  500, 'system:station:add',    '电站新增', 2, NULL, NULL, 1, NULL, NULL, 1, 0, NULL),
  (502,  500, 'system:station:edit',   '电站编辑', 2, NULL, NULL, 2, NULL, NULL, 1, 0, NULL),
  (503,  500, 'system:station:remove', '电站删除', 2, NULL, NULL, 3, NULL, NULL, 1, 0, NULL);

-- 超级管理员（role 1）关联全部菜单资源
INSERT IGNORE INTO `sys_role_permission` (`role_id`, `perm_id`)
SELECT 1, `perm_id` FROM `sys_permission`;
