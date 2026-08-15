-- =====================================================================
-- EnergyX 储能管理平台 · 告警规则种子数据（手工部署脚本，与 85_rule.sql 平行）
-- 87_alarm_rule_seed.sql：为前端「告警中心 → 告警规则」提供启用中的演示/基线规则
-- 作用产品：snd_ess_pcs（product_id=1，储能变流器 PCS）
-- 说明：幂等（INSERT IGNORE 靠 uk_rule_code (tenant_id, rule_code) 去重），可安全重复执行。
-- =====================================================================

INSERT IGNORE INTO `es_alarm`.`iot_alarm_rule`
  (`tenant_id`, `rule_code`, `rule_name`, `product_id`, `device_id`, `trigger_type`,
   `condition`, `severity`, `silence_seconds`, `recovery`, `status`, `description`, `create_by`)
VALUES
  (1, 'ALM_TEMP_HIGH',     '电芯高温告警',       1, NULL, 1,
   JSON_OBJECT('metric','cellTemp','op','GTE','value',60,'windowSec',60), 3, 300,
   JSON_OBJECT('metric','cellTemp','op','LT','value',55), 1, 'PCS 电芯温度 ≥60℃ 持续 60s 触发严重告警，恢复阈值 55℃', 1),
  (1, 'ALM_TEMP_WARN',     '电芯温度预警',       1, NULL, 1,
   JSON_OBJECT('metric','cellTemp','op','GTE','value',50,'windowSec',30), 1, 180,
   NULL, 1, 'PCS 电芯温度 ≥50℃ 持续 30s 触发提示级预警', 1),
  (1, 'ALM_SOC_LOW',       '电量过低告警',       1, NULL, 1,
   JSON_OBJECT('metric','soc','op','LTE','value',20,'windowSec',60), 2, 600,
   JSON_OBJECT('metric','soc','op','GTE','value',30), 1, 'PCS 荷电状态 ≤20% 持续 60s 触发一般告警，恢复阈值 30%', 1),
  (1, 'ALM_OVER_VOLTAGE',  '母线过压告警',       1, NULL, 1,
   JSON_OBJECT('metric','voltage','op','GTE','value',800,'windowSec',30), 3, 300,
   JSON_OBJECT('metric','voltage','op','LTE','value',760), 1, 'PCS 母线电压 ≥800V 持续 30s 触发严重告警，恢复阈值 760V', 1),
  (1, 'ALM_BMS_FAULT',     'BMS 故障事件告警',   1, NULL, 2,
   JSON_OBJECT('event','bmsFault'), 4, 300,
   NULL, 1, 'BMS 上报 bmsFault 故障事件即触发危急告警', 1);
