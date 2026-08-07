-- =====================================================================
-- EnergyX 储能管理平台 · TDengine 示例产品完整建模
-- 20_sample_stable.sql —— snd_ess_pcs（储能变流器）完整列 + 子表 + 写入 + 连续查询
-- 版本：v1.0    日期：2026-08-06
-- 说明：此文件是 10_stable.sql 模板的具体化，供开发者与测试理解完整建模流程。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 完整属性宽表（对齐 20_product.sql 种子物模型，可替换上面示例）
-- ---------------------------------------------------------------------
CREATE STABLE IF NOT EXISTS st_prop_snd_ess_pcs (
  ts        TIMESTAMP,
  msg_id    NCHAR(64),
  data_type NCHAR(16),
  soc       FLOAT,
  voltage   FLOAT,
  current   FLOAT,
  power     FLOAT,
  temp      FLOAT,
  run_mode  INT
) TAGS (
  device_id    NCHAR(64),
  station_id   NCHAR(32),
  enterprise_id NCHAR(32),
  product_key  NCHAR(64)
);

-- ---------------------------------------------------------------------
-- 2. 子表创建（一设备一子表；也可在 INSERT 时自动建表）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS dev_1100000000000000001
  USING st_prop_snd_ess_pcs
  TAGS ('1100000000000000001', 'ST00001', 'ENT00001', 'snd_ess_pcs');

-- ---------------------------------------------------------------------
-- 3. 写入（自动建表 + 多列批量插入；建议接入服务聚合 5s 上报为一条）
--    msg_id 为幂等去重键：TDengine 按 (ts, msg_id) 去重（INSERT IGNORE）
-- ---------------------------------------------------------------------
INSERT INTO dev_1100000000000000001
  USING st_prop_snd_ess_pcs TAGS ('1100000000000000001', 'ST00001', 'ENT00001', 'snd_ess_pcs')
  VALUES
  ('2026-08-06 02:00:00.000', 'msg-20260806-00001', 'report', 85.2, 691.3, -12.5, -8.6, 31.2, 2),
  ('2026-08-06 02:00:05.000', 'msg-20260806-00002', 'report', 85.1, 691.1, -12.4, -8.6, 31.1, 2);

-- ---------------------------------------------------------------------
-- 4. 事件写入（JSON 载荷）
-- ---------------------------------------------------------------------
INSERT INTO dev_1100000000000000001_evt
  USING st_event TAGS ('1100000000000000001', 'ST00001', 'ENT00001', 'snd_ess_pcs')
  VALUES
  ('2026-08-06 02:01:00.000', 'evt-20260806-00001', 'overTemp', 3, 'ALM_TEMP_HIGH',
   '{"temp":61.5,"threshold":60,"cellNo":"B12"}');

-- ---------------------------------------------------------------------
-- 5. 连续查询：原始宽表 → 1 分钟降采样（写入 iot_tsdb_agg）
--    TDengine 3.x 连续查询语法，按标签分组
-- ---------------------------------------------------------------------
CREATE CONTINUOUS QUERY cq_prop_1m_snd_ess_pcs
ON iot_tsdb_raw
BEGIN
  SELECT _wstart AS ts,
         max(soc) AS soc_max, min(soc) AS soc_min, avg(soc) AS soc_avg,
         max(voltage) AS voltage_max, min(voltage) AS voltage_min, avg(voltage) AS voltage_avg,
         max(current) AS current_max, min(current) AS current_min, avg(current) AS current_avg,
         max(power) AS power_max, min(power) AS power_min, avg(power) AS power_avg,
         max(temp) AS temp_max, min(temp) AS temp_min, avg(temp) AS temp_avg
  FROM st_prop_snd_ess_pcs
  WHERE run_mode IS NOT NULL
  INTERVAL(1m)
  INTO iot_tsdb_agg.st_prop_1m_snd_ess_pcs
  GROUP BY device_id, station_id, enterprise_id, product_key
END;

-- ---------------------------------------------------------------------
-- 6. 典型查询（能源驾驶舱）
-- ---------------------------------------------------------------------
-- 某设备最近 24h SOC 曲线：
--   SELECT _wstart, last(soc) FROM st_prop_snd_ess_pcs
--   WHERE device_id = '1100000000000000001' AND ts >= now - 24h INTERVAL(15m);
--
-- 某电站当日充放电电量：
--   SELECT _wstart, sum(power)/60 AS energy_kwh FROM st_prop_snd_ess_pcs
--   WHERE station_id = 'ST00001' AND ts >= today INTERVAL(1h);
