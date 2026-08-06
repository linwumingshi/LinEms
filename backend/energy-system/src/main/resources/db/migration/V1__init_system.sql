-- =====================================================================
-- 深圳三多能源储能管理平台 · system 域（energy-system 服务）
-- Flyway V1：租户 / 企业组织树 / RBAC / 操作审计
-- 注意：本地库已由 sql/mysql/10_sys.sql 手工初始化，
--       V1 全部语句幂等（IF NOT EXISTS / INSERT IGNORE），
--       配合 baseline-version=0 在种子库与全新库均可安全执行。
-- =====================================================================

CREATE TABLE IF NOT EXISTS `sys_tenant` (
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

CREATE TABLE IF NOT EXISTS `sys_enterprise` (
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

CREATE TABLE IF NOT EXISTS `sys_user` (
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

CREATE TABLE IF NOT EXISTS `sys_role` (
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

CREATE TABLE IF NOT EXISTS `sys_user_role` (
  `id`      BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL,
  `role_id` BIGINT NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_role` (`user_id`, `role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户-角色';

CREATE TABLE IF NOT EXISTS `sys_permission` (
  `perm_id`       BIGINT      NOT NULL AUTO_INCREMENT,
  `parent_id`     BIGINT      NOT NULL DEFAULT 0,
  `perm_code`     VARCHAR(64) NOT NULL COMMENT '权限点编码，如 station:monitor:view',
  `perm_name`     VARCHAR(64) NOT NULL,
  `perm_type`     TINYINT     NOT NULL COMMENT '1菜单 2按钮 3数据',
  `resource_type` VARCHAR(32)          DEFAULT NULL COMMENT '资源类型：DEVICE/STRATEGY/ALARM/STATION',
  `path`          VARCHAR(256)         DEFAULT NULL COMMENT '前端路由',
  `sort`          INT         NOT NULL DEFAULT 0,
  `create_time`   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`perm_id`),
  UNIQUE KEY `uk_perm_code` (`perm_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='权限点';

CREATE TABLE IF NOT EXISTS `sys_role_permission` (
  `id`      BIGINT NOT NULL AUTO_INCREMENT,
  `role_id` BIGINT NOT NULL,
  `perm_id` BIGINT NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_perm` (`role_id`, `perm_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色-权限';

-- 操作审计（按月分区；调度任务按月预建分区）
CREATE TABLE IF NOT EXISTS `sys_operator_log` (
  `log_id`        BIGINT       NOT NULL COMMENT '日志ID（雪花）',
  `tenant_id`     BIGINT       NOT NULL,
  `operator_id`   BIGINT       NOT NULL COMMENT '操作人',
  `operator_name` VARCHAR(64)           DEFAULT NULL,
  `action`        VARCHAR(64)  NOT NULL COMMENT '操作，如 device:create',
  `target_type`   VARCHAR(32)           DEFAULT NULL COMMENT '对象类型：DEVICE/STRATEGY/...',
  `target_id`     VARCHAR(64)           DEFAULT NULL,
  `ip`            VARCHAR(64)           DEFAULT NULL,
  `user_agent`    VARCHAR(256)          DEFAULT NULL,
  `trace_id`      VARCHAR(64)           DEFAULT NULL COMMENT '全链路追踪ID',
  `detail`        JSON                  DEFAULT NULL COMMENT '变更明细',
  `create_time`   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
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
-- 种子数据（幂等）
-- =====================================================================
INSERT IGNORE INTO `sys_tenant` (`tenant_id`, `tenant_code`, `tenant_name`, `status`) VALUES
(1, 'SND', '深圳三多能源', 1);

INSERT IGNORE INTO `sys_enterprise` (`enterprise_id`, `tenant_id`, `parent_id`, `path`, `level`, `enterprise_code`, `enterprise_name`) VALUES
(1, 1, 0, '/1/', 1, 'SND-HQ', '三多能源集团本部');

INSERT IGNORE INTO `sys_user` (`user_id`, `tenant_id`, `username`, `password`, `real_name`, `status`) VALUES
(1, 1, 'admin', '{noop}admin123', '系统管理员', 1);

INSERT IGNORE INTO `sys_role` (`role_id`, `tenant_id`, `role_code`, `role_name`, `data_scope`) VALUES
(1, 1, 'SUPER_ADMIN', '超级管理员', 4);

INSERT IGNORE INTO `sys_user_role` (`user_id`, `role_id`) VALUES (1, 1);
