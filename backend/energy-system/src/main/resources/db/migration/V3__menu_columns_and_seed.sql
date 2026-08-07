-- =====================================================================
-- EnergyX 储能管理平台 · system 域（energy-system 服务）
-- Flyway V3：sys_permission 升级为菜单资源表（对齐若依 sys_menu 语义）+ 系统菜单种子
-- 说明：加列幂等（INFORMATION_SCHEMA 判断，列已存在则跳过），
--      既有种子库（10_sys.sql 旧结构）、全新空库（Flyway 全建）、
--      以及按最新 10_sys.sql（已含新列）手工种子建库，三条路径均可安全执行。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. sys_permission 补充菜单展示字段（幂等加列）
--    MySQL 8.0 无 ADD COLUMN IF NOT EXISTS，借助临时存过 + 动态 SQL 判断。
-- ---------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_sanduo_add_column;
DELIMITER //
CREATE PROCEDURE sp_sanduo_add_column(
    IN p_table_name VARCHAR(64),
    IN p_column_name VARCHAR(64),
    IN p_column_ddl VARCHAR(1024)
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table_name
          AND COLUMN_NAME = p_column_name
    ) THEN
        SET @sanduo_ddl = p_column_ddl;
        PREPARE sanduo_stmt FROM @sanduo_ddl;
        EXECUTE sanduo_stmt;
        DEALLOCATE PREPARE sanduo_stmt;
    END IF;
END //
DELIMITER ;

CALL sp_sanduo_add_column('sys_permission', 'icon',
    'ALTER TABLE `sys_permission` ADD COLUMN `icon` VARCHAR(100) NOT NULL DEFAULT ''#'' COMMENT ''菜单图标''');
CALL sp_sanduo_add_column('sys_permission', 'component',
    'ALTER TABLE `sys_permission` ADD COLUMN `component` VARCHAR(255) DEFAULT NULL COMMENT ''前端组件路径''');
CALL sp_sanduo_add_column('sys_permission', 'visible',
    'ALTER TABLE `sys_permission` ADD COLUMN `visible` TINYINT NOT NULL DEFAULT 0 COMMENT ''是否显示：0显示 1隐藏''');
CALL sp_sanduo_add_column('sys_permission', 'status',
    'ALTER TABLE `sys_permission` ADD COLUMN `status` TINYINT NOT NULL DEFAULT 0 COMMENT ''状态：0正常 1停用''');
CALL sp_sanduo_add_column('sys_permission', 'remark',
    'ALTER TABLE `sys_permission` ADD COLUMN `remark` VARCHAR(500) DEFAULT NULL COMMENT ''备注''');
CALL sp_sanduo_add_column('sys_permission', 'update_time',
    'ALTER TABLE `sys_permission` ADD COLUMN `update_time` DATETIME(3) DEFAULT NULL COMMENT ''更新时间''');

DROP PROCEDURE sp_sanduo_add_column;

-- ---------------------------------------------------------------------
-- 2. 系统管理菜单种子（perm_type：1菜单 2按钮 3数据；perm_code 即 @ss.hasPermi 权限标识）
--    目录/菜单节点带 path+component 供前端动态路由；按钮节点仅承载权限标识。
-- ---------------------------------------------------------------------
INSERT IGNORE INTO `sys_permission`
  (`perm_id`, `parent_id`, `perm_code`, `perm_name`, `perm_type`, `resource_type`, `path`, `sort`, `icon`, `component`, `visible`, `status`, `remark`)
VALUES
  -- 系统管理目录
  (1,    0,   'system',                '系统管理', 1, NULL, 'system',   1, 'setting', NULL,                0, 0, '系统管理目录'),
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
  -- 单位管理
  (400,  1,   'system:enterprise:list', '单位管理', 1, NULL, 'system/enterprise', 4, 'office-building', 'system/enterprise/index', 0, 0, '单位管理菜单'),
  (401,  400, 'system:enterprise:add',  '单位新增', 2, NULL, NULL, 1, NULL, NULL, 1, 0, NULL),
  (402,  400, 'system:enterprise:edit', '单位编辑', 2, NULL, NULL, 2, NULL, NULL, 1, 0, NULL),
  (403,  400, 'system:enterprise:remove', '单位删除', 2, NULL, NULL, 3, NULL, NULL, 1, 0, NULL);
