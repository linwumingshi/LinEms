-- 防重复计划：同电站同日期仅允许一个计划（配合 EmsPlanService.generate 前置校验 + writer 幂等写，
-- 杜绝手动重复生成 / 定时与手动同日导致的重复下发与重复写点）。
-- 已存在历史数据不含同 (station_id, plan_date) 重复行，可安全加唯一键。
ALTER TABLE `ems_plan` ADD UNIQUE KEY `uk_plan_station_date` (`station_id`, `plan_date`);
