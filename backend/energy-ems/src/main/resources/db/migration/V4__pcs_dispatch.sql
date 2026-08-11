-- =====================================================================
-- EnergyX 储能管理平台 · ems 域（energy-ems 服务）
-- V4__pcs_dispatch.sql —— 下发设备按电站解析（P0-2：PCS 按电站配置）
-- 版本：v1.3    日期：2026-08-11
-- 设计依据：P0-2 下发设备从设备表（es_device.iot_device）解析，支持一电站多 PCS，
--   每个计划点对每台 PCS 各建一条执行记录（device_id 真实）。
--   原唯一键 (plan_id, plan_time) 假定一点一条记录；多 PCS 后同一点多条记录（每设备一条）会撞键，
--   扩展为 (plan_id, plan_time, device_id)。
-- =====================================================================

ALTER TABLE `ems_execution_record`
  DROP INDEX `uk_exec_plan_time`,
  ADD UNIQUE KEY `uk_exec_plan_time_dev` (`plan_id`, `plan_time`, `device_id`);
