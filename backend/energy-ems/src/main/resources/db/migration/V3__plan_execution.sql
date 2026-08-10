-- =====================================================================
-- EnergyX 储能管理平台 · ems 域（energy-ems 服务）
-- V3__plan_execution.sql —— 计划执行闭环（阶段：P0 执行闭环）
-- 版本：v1.0    日期：2026-08-10
-- 设计依据：充放电计划执行闭环审计结论
--  1) ems_execution_record 增加 plan_time（计划点时刻，调度器按点到点下发）与 state（点执行状态）
--  2) 唯一键 (plan_id, plan_time)：调度器到点下发防重复（幂等）
--  3) 旧数据兼容：plan_time 回填 '00:00'（历史一次性全量下发遗留，无调度语义）
-- =====================================================================

ALTER TABLE `ems_execution_record`
  ADD COLUMN `plan_time` TIME NOT NULL DEFAULT '00:00' COMMENT '计划点时刻（5min粒度，调度器到点下发锚点）' AFTER `command_id`,
  ADD COLUMN `state` TINYINT NOT NULL DEFAULT 0 COMMENT '0待下发 1已下发 2成功 3失败 4超时' AFTER `action`,
  ADD UNIQUE KEY `uk_exec_plan_time` (`plan_id`, `plan_time`);
