-- =====================================================================
-- EnergyX 储能管理平台 · ems 域（energy-ems 服务）
-- V3__plan_execution.sql —— 计划执行闭环（阶段：P0 执行闭环）
-- 版本：v1.2    日期：2026-08-10
-- 设计依据：充放电计划执行闭环审计结论
--  1) ems_execution_record 增加 plan_time（计划点时刻，调度器按点到点下发）与 state（点执行状态）
--  2) 唯一键 (plan_id, plan_time)：调度器到点下发防重复（幂等）
--  3) 历史数据兼容（v1.2 修复）：
--     旧版 dispatch 为一次性全量下发，同一计划在同一时刻插入 N 条记录且无 plan_time 语义
--     （新列默认 '00:00' 会撞唯一键）→ 直接清理旧缺陷产生的执行记录（无调度语义、无 ACK 回执，
--     且计划状态已随旧代码卡死，无保留价值），再建唯一键，保证迁移幂等可重复执行。
-- =====================================================================

-- 3.1 清理旧缺陷执行记录：旧一次性全量下发残留（每计划 96 条 '00:00' 假记录），新调度语义下无意义
DELETE FROM `ems_execution_record`;

ALTER TABLE `ems_execution_record`
  ADD COLUMN `plan_time` TIME NOT NULL DEFAULT '00:00' COMMENT '计划点时刻（5min粒度，调度器到点下发锚点）' AFTER `command_id`,
  ADD COLUMN `state` TINYINT NOT NULL DEFAULT 0 COMMENT '0待下发 1已下发 2成功 3失败 4超时' AFTER `action`,
  ADD UNIQUE KEY `uk_exec_plan_time` (`plan_id`, `plan_time`);
