-- =====================================================================
-- EnergyX 储能管理平台 · TDengine 超级表模板
-- 10_stable.sql —— 属性宽表 / 事件 / 电芯聚合 / 降采样
-- 版本：v1.0    日期：2026-08-06
-- 设计依据：决策 B.1（每产品一张宽表 STABLE，否决每指标一表）
-- 约定：
--   * 子表名 = device_id（一设备一子表），TAGS 冗余 station/enterprise/product 便于跨产品查询
--   * 属性列名 = 物模型 identifier（映射自 iot_thing_model_identifier 投影表）
--   * 新增属性 → ALTER STABLE ADD COLUMN（随物模型版本演进，低频）
--   * 属性列名 = TSL identifier 原样（snake/camel 都不转换），须与写路径 TdengineSqlBuilder 一致
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 属性宽表模板（示例产品 snd_ess_pcs，完整列见 20_sample_stable.sql）
--    列 = 公共列（ts/msg_id/data_type）+ 产品属性列
-- ---------------------------------------------------------------------
CREATE STABLE IF NOT EXISTS st_prop_snd_ess_pcs (
  ts        TIMESTAMP,
  msg_id    NCHAR(64),          -- 消息ID（幂等去重）
  data_type NCHAR(16),          -- report/setReply 等
  soc       FLOAT,
  voltage   FLOAT,
  current   FLOAT,
  power     FLOAT,
  temp      FLOAT,
  `runMode` INT
) TAGS (
  device_id    NCHAR(64),
  station_id   NCHAR(32),
  enterprise_id NCHAR(32),
  product_key  NCHAR(64)
);

-- ---------------------------------------------------------------------
-- 2. 事件表（统一一张，事件量远小于属性；payload 用 JSON 列承载可变载荷）
-- ---------------------------------------------------------------------
CREATE STABLE IF NOT EXISTS st_event (
  ts         TIMESTAMP,
  event_id   NCHAR(64),          -- 事件幂等ID
  event_name NCHAR(64),          -- 物模型事件标识，如 overTemp
  severity   INT,                -- 1提示 2一般 3严重 4危急
  code       NCHAR(32),          -- 事件码
  payload    JSON                -- 可变事件载荷
) TAGS (
  device_id    NCHAR(64),
  station_id   NCHAR(32),
  enterprise_id NCHAR(32),
  product_key  NCHAR(64)
);

-- ---------------------------------------------------------------------
-- 3. 电芯聚合表（单体明细不落 MySQL；BMS 上报聚合值 + cell_no 打标签）
-- ---------------------------------------------------------------------
CREATE STABLE IF NOT EXISTS st_cell_agg (
  ts        TIMESTAMP,
  v_max     FLOAT,               -- 单体最高电压
  v_min     FLOAT,               -- 单体最低电压
  v_avg     FLOAT,
  v_diff    FLOAT,               -- 压差
  t_max     FLOAT,               -- 单体最高温度
  t_min     FLOAT,
  t_diff    FLOAT,               -- 温差
  soc       FLOAT,
  soh       FLOAT,               -- 健康度（AI 评估回填）
  imbalance FLOAT                -- 不一致性指标
) TAGS (
  device_id    NCHAR(64),        -- BMS 设备
  cell_no      NCHAR(16),        -- 电芯编号
  station_id   NCHAR(32),
  enterprise_id NCHAR(32),
  product_key  NCHAR(64)
);

-- ---------------------------------------------------------------------
-- 4. 降采样宽表（1 分钟 / 1 小时，位于 iot_tsdb_agg）
--    列 = 原始宽表属性列（max/min/avg 三元组按需），由连续查询写入
-- ---------------------------------------------------------------------
CREATE STABLE IF NOT EXISTS st_prop_1m_snd_ess_pcs (
  ts        TIMESTAMP,
  soc_max   FLOAT, soc_min FLOAT, soc_avg FLOAT,
  voltage_max FLOAT, voltage_min FLOAT, voltage_avg FLOAT,
  current_max FLOAT, current_min FLOAT, current_avg FLOAT,
  power_max FLOAT, power_min FLOAT, power_avg FLOAT,
  temp_max  FLOAT, temp_min FLOAT, temp_avg FLOAT
) TAGS (
  device_id    NCHAR(64),
  station_id   NCHAR(32),
  enterprise_id NCHAR(32),
  product_key  NCHAR(64)
);
