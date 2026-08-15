-- =====================================================================
-- EnergyX 储能管理平台 · 消息通知（energy-notify 服务，端口 8117）
-- 88_notify.sql：通知配置 + 通知模板
-- 说明：幂等（DROP IF EXISTS 重建），仅初始建库使用；增量环境用 Flyway/手工执行
-- =====================================================================
CREATE DATABASE IF NOT EXISTS `es_notify` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE `es_notify`;

-- ---------------------------------------------------------------------
-- 1. 通知配置（发往哪、怎么发；渠道密钥外置 JSON）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `iot_notify_config`;
CREATE TABLE `iot_notify_config` (
  `config_id`     BIGINT       NOT NULL COMMENT '配置ID（雪花）',
  `tenant_id`     BIGINT       NOT NULL DEFAULT 1,
  `config_code`   VARCHAR(64)  NOT NULL COMMENT '配置编码，租户内唯一，如 WEBHOOK_OPS',
  `config_name`   VARCHAR(128) NOT NULL COMMENT '配置名称',
  `channel`       VARCHAR(32)  NOT NULL COMMENT '渠道：WEBHOOK/WECOM/DINGTALK/EMAIL（预留 SMS/VOICE）',
  `channel_config` JSON         NOT NULL COMMENT '渠道配置（JSON）：
      WEBHOOK:{"url":"https://...","headers":{"X-Auth":"..."}}
      WECOM:  {"webhook":"https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=..."}
      DINGTALK:{"webhook":"https://oapi.dingtalk.com/robot/send?access_token=...","secret":""}
      EMAIL:  {"host":"smtp.qq.com","port":465,"username":"xx@qq.com","password":"授权码","from":"xx@qq.com","ssl":true}',
  `status`        TINYINT      NOT NULL DEFAULT 1 COMMENT '0停用 1启用',
  `description`   VARCHAR(256)          DEFAULT NULL,
  `create_by`     BIGINT                DEFAULT NULL,
  `create_time`   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`config_id`),
  UNIQUE KEY `uk_config_code` (`tenant_id`, `config_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知配置';

-- ---------------------------------------------------------------------
-- 2. 通知模板（发什么、什么格式；占位符 ${xxx} 由发送上下文渲染）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `iot_notify_template`;
CREATE TABLE `iot_notify_template` (
  `template_id`     BIGINT       NOT NULL COMMENT '模板ID（雪花）',
  `tenant_id`       BIGINT       NOT NULL DEFAULT 1,
  `template_code`   VARCHAR(64)  NOT NULL COMMENT '模板编码，租户内唯一',
  `template_name`   VARCHAR(128) NOT NULL COMMENT '模板名称',
  `message_type`    VARCHAR(32)  NOT NULL COMMENT '消息类型：ALARM告警/SCENE场景联动/DEVICE_EVENT设备事件/SYSTEM系统',
  `channel`         VARCHAR(32)  NOT NULL COMMENT '绑定渠道：WEBHOOK/WECOM/DINGTALK/EMAIL（与配置渠道一致才可发送）',
  `title_template`  VARCHAR(255)          DEFAULT NULL COMMENT '标题模板（邮件主题/企微标题等），支持 ${xxx}',
  `content_template` TEXT         NOT NULL COMMENT '内容模板，支持 ${xxx} 占位符',
  `variables`       JSON                  DEFAULT NULL COMMENT '占位符说明：[{"key":"deviceName","desc":"设备名称"},...]',
  `status`          TINYINT      NOT NULL DEFAULT 1 COMMENT '0停用 1启用',
  `description`     VARCHAR(256)          DEFAULT NULL,
  `create_by`       BIGINT                DEFAULT NULL,
  `create_time`     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`template_id`),
  UNIQUE KEY `uk_template_code` (`tenant_id`, `template_code`),
  KEY `idx_template_channel` (`channel`),
  KEY `idx_template_type` (`message_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知模板';
