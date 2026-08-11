# P1-2 需量管理 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增 METER 设备类型承载进线功率遥测，按 15min 固定槽位实时检测站点需量，超限即向站内 PCS 下发削峰放电指令并留痕、发布需量超限事件，估算基本电费节省并接入收益核算，提供需量管理前端页。

**Architecture:** 1min `@Scheduled` + 分布式锁（复用 `PlanExecutionScheduler` 模式）遍历有需量配置的站；`MeterDeviceMapper` 解析站内电表 → `TsdbClient.history` 整日拉取内存按当前槽位 filter → `DemandDetector` 纯函数判定 → `DemandShaveClient` 向活跃 PCS 均分下 `DISCHARGE` 并 upsert `ems_demand_record` 留痕 → `DemandAlarmProducer` 发布 `iot-thing-event(demandOverLimit)`。节省估算由 `DemandSavingsEstimator` 纯函数聚合槽位记录，接入 `EmsRevenueService.demandSavings`（原恒 0）。

**Tech Stack:** Java 17 + Spring Boot 3 + MyBatis-Plus（energy-ems）；Vue 3 + TS + ECharts（frontend）；MySQL（Flyway V6）、TDengine（TSDB 只读）。

## Global Constraints

- **代码格式**：Maven `validate` 阶段校验 `spring-javaformat`，未格式化编译失败。后端每个任务的验证命令以 `mvn -pl energy-ems spring-javaformat:apply` 开头。
- **数据库**：新表在 `backend/energy-ems/src/main/resources/db/migration/` 建 **V6__demand.sql**（V5 已是最新）。energy-product 的 METER 种子必须建**新迁移** `backend/energy-product/src/main/resources/db/migration/V2__seed_meter.sql`（V1 已应用，追加到 V1 不会在已存在库生效）。
- **表命名/结构**：`ems_demand_config`（UNIQUE(tenant_id, station_id)）、`ems_demand_record`（UNIQUE(station_id, window_start)），BIGINT AUTO_INCREMENT PK，DATETIME(3)，create_time/update_time 与 `ems_station_meta` 一致。
- **动作枚举**：`action` VARCHAR(16)，仅 `NONE` / `SHED` / `SHED_FAILED` / `ALARM_ONLY`。
- **租户隔离**：`TenantContext` 只在 HTTP 请求线程生效；**调度线程无租户上下文**。`DemandDetectScheduler` 遍历/读写一律显式传 `config.getTenantId()`。HTTP 服务（`EmsDemandConfigService`、`DemandController`）用 `requireTenant()`。
- **设备解析**：跨库读 `es_device.iot_device` 用 @Select 裸 SQL（`PcsDeviceMapper` 模式），显式 `tenant_id` + `deleted=0` + `status IN (2,3)`。
- **product key**：电表 `@Value("${energyx.ems.meter-product-key:snd_ess_meter}")`、PCS `@Value("${energyx.ems.product-key:snd_ess_pcs}")`，均为 Java 默认值（不写任何 yml/Nacos 文件，`snd_ess_pcs` 先例即如此）。
- **削峰指令**：`CommandClient.dispatch(productKey, deviceName, "DISCHARGE", params, 0L)`，params = `{action:"DISCHARGE", power, socTarget, time}`（仿 `EmsPlanService.java:311` 定时下发 createBy=0L）。
- **固定 15min 槽位**：96 槽/天，槽位起点 = 时刻向下取整到 15min；检测值 = 槽位内当前已积累样本均值（早期预警），记录随每分钟 upsert 定型。
- **告警幂等**：同一槽位仅首次越限发布一次 `iot-thing-event`；槽位一旦定型超限，后续 NONE 写入不得覆盖削峰痕迹。
- **前端**：`npx vue-tsc --noEmit` + `npm run build`。`frontend/src/utils/dicts.ts` 的 `deviceTypeOptions` 与 `DEVICE_TYPE_TEXT` 必须加 `'METER'`（设备管理页展示电表）。路由/侧边栏仿 `ems/revenue`。
- **测试**：Mockito（`mockStatic` 可用，`EmsPlanServiceTest` 先例）；实体级 BaseMapper mock 需 `TableInfoHelper.initTableInfo`（`EmsStationMetaServiceTest` 先例）。
- **不改动**：`PlanGenerator` 的 DEMAND 分支、`ems_execution_record` 计划语义、告警规则种子（用户自建）。

---

### Task 1: 需量数据模型（V6 迁移 + 实体 + config/record 服务）

**Files:**
- Create: `backend/energy-ems/src/main/resources/db/migration/V6__demand.sql`
- Create: `backend/energy-ems/src/main/java/com/energyx/ems/entity/EmsDemandConfig.java`
- Create: `backend/energy-ems/src/main/java/com/energyx/ems/entity/EmsDemandRecord.java`
- Create: `backend/energy-ems/src/main/java/com/energyx/ems/mapper/EmsDemandConfigMapper.java`
- Create: `backend/energy-ems/src/main/java/com/energyx/ems/mapper/EmsDemandRecordMapper.java`
- Create: `backend/energy-ems/src/main/java/com/energyx/ems/service/EmsDemandConfigService.java`
- Create: `backend/energy-ems/src/main/java/com/energyx/ems/service/EmsDemandRecordService.java`
- Test: `backend/energy-ems/src/test/java/com/energyx/ems/service/EmsDemandConfigServiceTest.java`
- Test: `backend/energy-ems/src/test/java/com/energyx/ems/service/EmsDemandRecordServiceTest.java`

**Interfaces:**
- Consumes: `TenantContext`（com.energyx.common.tenant）、`BusinessException`/`ErrorCode.UNAUTHORIZED`（com.energyx.common.exception）、`EmsStationMeta` 实体注解模式。
- Produces（后续任务依赖的精确签名）：
  - `EmsDemandConfigService.getByStation(Long stationId) → EmsDemandConfig`（HTTP，需租户）
  - `EmsDemandConfigService.upsert(EmsDemandConfig) → EmsDemandConfig`（HTTP，需租户）
  - `EmsDemandConfigService.listAll() → List<EmsDemandConfig>`（调度线程，全租户）
  - `EmsDemandRecordService.getByStationAndWindow(Long tenantId, Long stationId, LocalDateTime windowStart) → EmsDemandRecord`
  - `EmsDemandRecordService.listByRange(Long tenantId, Long stationId, LocalDateTime start, LocalDateTime end) → List<EmsDemandRecord>`（windowStart 升序）
  - `EmsDemandRecordService.upsert(EmsDemandRecord) → EmsDemandRecord`（幂等，返回入参）
  - 实体字段：`EmsDemandConfig{demandConfigId, tenantId, stationId, demandLimitKw, demandRate, createTime, updateTime}`；`EmsDemandRecord{demandRecordId, tenantId, stationId, windowStart, windowEnd, demandKw, limitKw, overLimit(Boolean), shavedKw, action, createTime}`

- [ ] **Step 1: 写 V6 迁移**

`backend/energy-ems/src/main/resources/db/migration/V6__demand.sql`（文件头注释仿 V5__revenue_meta.sql）：

```sql
-- =====================================================================
-- EnergyX 储能管理平台 · ems 域（energy-ems 服务）
-- V6__demand.sql —— 需量管理（P1-2）
-- 版本：v1.5    日期：2026-08-11
-- 说明：站点需量配置（限值/费率）+ 每站每 15min 槽位检测留痕。
-- =====================================================================

CREATE TABLE `ems_demand_config` (
  `demand_config_id` BIGINT        NOT NULL AUTO_INCREMENT,
  `tenant_id`        BIGINT        NOT NULL,
  `station_id`       BIGINT        NOT NULL,
  `demand_limit_kw`  DECIMAL(10,2)          DEFAULT NULL COMMENT '需量限值 kW（>0 启用检测）',
  `demand_rate`      DECIMAL(8,4)           DEFAULT NULL COMMENT '需量费率 ¥/kW·月',
  `create_time`      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`demand_config_id`),
  UNIQUE KEY `uk_demand_config_station` (`tenant_id`, `station_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='需量管理站点配置';

CREATE TABLE `ems_demand_record` (
  `demand_record_id` BIGINT      NOT NULL AUTO_INCREMENT,
  `tenant_id`        BIGINT      NOT NULL,
  `station_id`       BIGINT      NOT NULL,
  `window_start`     DATETIME(3) NOT NULL COMMENT '槽位起点',
  `window_end`       DATETIME(3) NOT NULL COMMENT '槽位终点',
  `demand_kw`        DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '槽位实际需量（15min 平均功率 kW）',
  `limit_kw`         DECIMAL(10,2)          DEFAULT NULL COMMENT '限值快照 kW',
  `over_limit`       TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '是否超限',
  `shaved_kw`        DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '削峰放电功率 kW（未削峰=0）',
  `action`           VARCHAR(16) NOT NULL DEFAULT 'NONE' COMMENT 'NONE/SHED/SHED_FAILED/ALARM_ONLY',
  `create_time`      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`demand_record_id`),
  UNIQUE KEY `uk_demand_record_window` (`station_id`, `window_start`),
  KEY `idx_demand_record_station_time` (`station_id`, `window_start`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='需量检测槽位记录';
```

- [ ] **Step 2: 写两个实体**（仿 `EmsStationMeta.java`，注解/填充器完全一致）

`backend/energy-ems/src/main/java/com/energyx/ems/entity/EmsDemandConfig.java`：

```java
package com.energyx.ems.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 需量管理站点配置（ems_demand_config）。tenant+station 唯一（uk_demand_config_station）。 */
@Data
@TableName("ems_demand_config")
public class EmsDemandConfig {

	@TableId(type = IdType.AUTO)
	private Long demandConfigId;

	private Long tenantId;

	private Long stationId;

	/** 需量限值 kW（>0 启用检测） */
	private BigDecimal demandLimitKw;

	/** 需量费率 ¥/kW·月 */
	private BigDecimal demandRate;

	@TableField(value = "create_time", fill = FieldFill.INSERT)
	private LocalDateTime createTime;

	@TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
	private LocalDateTime updateTime;

}
```

`backend/energy-ems/src/main/java/com/energyx/ems/entity/EmsDemandRecord.java`：

```java
package com.energyx.ems.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 需量检测槽位记录（ems_demand_record）。每站每 15min 槽位一条（uk_demand_record_window）。 */
@Data
@TableName("ems_demand_record")
public class EmsDemandRecord {

	@TableId(type = IdType.AUTO)
	private Long demandRecordId;

	private Long tenantId;

	private Long stationId;

	/** 槽位起点 */
	private LocalDateTime windowStart;

	/** 槽位终点 */
	private LocalDateTime windowEnd;

	/** 槽位实际需量（15min 平均功率 kW） */
	private BigDecimal demandKw;

	/** 限值快照 kW */
	private BigDecimal limitKw;

	/** 是否超限 */
	private Boolean overLimit;

	/** 削峰放电功率 kW（未削峰=0） */
	private BigDecimal shavedKw;

	/** NONE/SHED/SHED_FAILED/ALARM_ONLY */
	private String action;

	@TableField(value = "create_time", fill = FieldFill.INSERT)
	private LocalDateTime createTime;

}
```

- [ ] **Step 3: 写两个 Mapper**

`backend/energy-ems/src/main/java/com/energyx/ems/mapper/EmsDemandConfigMapper.java`：

```java
package com.energyx.ems.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.energyx.ems.entity.EmsDemandConfig;
import org.apache.ibatis.annotations.Mapper;

/** 需量配置 Mapper。 */
@Mapper
public interface EmsDemandConfigMapper extends BaseMapper<EmsDemandConfig> {
}
```

`backend/energy-ems/src/main/java/com/energyx/ems/mapper/EmsDemandRecordMapper.java`：

```java
package com.energyx.ems.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.energyx.ems.entity.EmsDemandRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/** 需量槽位记录 Mapper。upsert 按 (station_id, window_start) 唯一键幂等。 */
@Mapper
public interface EmsDemandRecordMapper extends BaseMapper<EmsDemandRecord> {

	@Insert("""
			INSERT INTO ems_demand_record
			  (tenant_id, station_id, window_start, window_end, demand_kw, limit_kw, over_limit, shaved_kw, action)
			VALUES (#{tenantId}, #{stationId}, #{windowStart}, #{windowEnd}, #{demandKw}, #{limitKw}, #{overLimit}, #{shavedKw}, #{action})
			ON DUPLICATE KEY UPDATE
			  window_end = VALUES(window_end),
			  demand_kw = VALUES(demand_kw),
			  limit_kw = VALUES(limit_kw),
			  over_limit = VALUES(over_limit),
			  shaved_kw = VALUES(shaved_kw),
			  action = VALUES(action)
			""")
	int upsert(EmsDemandRecord rec);

}
```

- [ ] **Step 4: 写两个 Service**

`backend/energy-ems/src/main/java/com/energyx/ems/service/EmsDemandConfigService.java`（仿 `EmsStationMetaService`，多一个调度线程用的 `listAll`）：

```java
package com.energyx.ems.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.energyx.common.exception.BusinessException;
import com.energyx.common.exception.ErrorCode;
import com.energyx.common.tenant.TenantContext;
import com.energyx.ems.entity.EmsDemandConfig;
import com.energyx.ems.mapper.EmsDemandConfigMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/** 需量配置读写（P1-2）。upsert 按 (tenant_id, station_id) 唯一。 */
@Service
public class EmsDemandConfigService extends ServiceImpl<EmsDemandConfigMapper, EmsDemandConfig> {

	/** 查站点需量配置；未配置返回 null。 */
	public EmsDemandConfig getByStation(Long stationId) {
		return getOne(new LambdaQueryWrapper<EmsDemandConfig>().eq(EmsDemandConfig::getTenantId, requireTenant())
			.eq(EmsDemandConfig::getStationId, stationId));
	}

	/** upsert 幂等：同站已存在则原位更新（保留主键），否则插入并补租户。 */
	public EmsDemandConfig upsert(EmsDemandConfig cfg) {
		cfg.setTenantId(requireTenant());
		EmsDemandConfig hit = getByStation(cfg.getStationId());
		if (hit != null) {
			cfg.setDemandConfigId(hit.getDemandConfigId());
			updateById(cfg);
			return cfg;
		}
		cfg.setDemandConfigId(null);
		save(cfg);
		return cfg;
	}

	/** 全量配置（调度线程无租户上下文，遍历全部租户的参与站）。 */
	public List<EmsDemandConfig> listAll() {
		return list();
	}

	private long requireTenant() {
		Long t = TenantContext.getTenantId();
		if (t == null) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED, "缺少租户上下文");
		}
		return t;
	}

}
```

`backend/energy-ems/src/main/java/com/energyx/ems/service/EmsDemandRecordService.java`（租户由调用方显式传入，调度线程无上下文）：

```java
package com.energyx.ems.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.energyx.ems.entity.EmsDemandRecord;
import com.energyx.ems.mapper.EmsDemandRecordMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/** 需量槽位记录读写（P1-2）。租户 id 由调用方显式传入（调度线程无 TenantContext）。 */
@Service
public class EmsDemandRecordService {

	private final EmsDemandRecordMapper mapper;

	public EmsDemandRecordService(EmsDemandRecordMapper mapper) {
		this.mapper = mapper;
	}

	/** 查某站某槽位记录；无返回 null。 */
	public EmsDemandRecord getByStationAndWindow(Long tenantId, Long stationId, LocalDateTime windowStart) {
		return mapper.selectOne(new LambdaQueryWrapper<EmsDemandRecord>()
			.eq(EmsDemandRecord::getTenantId, tenantId)
			.eq(EmsDemandRecord::getStationId, stationId)
			.eq(EmsDemandRecord::getWindowStart, windowStart));
	}

	/** 按时间范围取槽位记录（windowStart 升序）。 */
	public List<EmsDemandRecord> listByRange(Long tenantId, Long stationId, LocalDateTime start, LocalDateTime end) {
		return mapper.selectList(new LambdaQueryWrapper<EmsDemandRecord>()
			.eq(EmsDemandRecord::getTenantId, tenantId)
			.eq(EmsDemandRecord::getStationId, stationId)
			.ge(EmsDemandRecord::getWindowStart, start)
			.le(EmsDemandRecord::getWindowStart, end)
			.orderByAsc(EmsDemandRecord::getWindowStart));
	}

	/** 槽位记录 upsert（按 station_id+window_start 幂等）。返回入参。 */
	public EmsDemandRecord upsert(EmsDemandRecord rec) {
		mapper.upsert(rec);
		return rec;
	}

}
```

- [ ] **Step 5: 写 config service 测试**（镜像 `EmsStationMetaServiceTest`）

`backend/energy-ems/src/test/java/com/energyx/ems/service/EmsDemandConfigServiceTest.java`：

```java
package com.energyx.ems.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.energyx.common.exception.BusinessException;
import com.energyx.common.tenant.TenantContext;
import com.energyx.common.tenant.TenantInfo;
import com.energyx.ems.entity.EmsDemandConfig;
import com.energyx.ems.mapper.EmsDemandConfigMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

/** EmsDemandConfigService.upsert 幂等：同站已存在原位更新，否则插入补租户；缺租户上下文抛异常。 */
class EmsDemandConfigServiceTest {

	@BeforeAll
	static void registerTableInfo() {
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), EmsDemandConfig.class);
	}

	@BeforeEach
	void setTenant() {
		TenantContext.set(new TenantInfo(7L, 100L));
	}

	@AfterEach
	void clearTenant() {
		TenantContext.clear();
	}

	private static EmsDemandConfigService newService(EmsDemandConfigMapper mapper) {
		EmsDemandConfigService svc = new EmsDemandConfigService();
		ReflectionTestUtils.setField(svc, "baseMapper", mapper);
		return svc;
	}

	@Test
	void getByStation_missingTenantThrows() {
		TenantContext.clear();
		EmsDemandConfigService svc = newService(mock(EmsDemandConfigMapper.class));
		assertThrows(BusinessException.class, () -> svc.getByStation(10L));
	}

	@Test
	void upsert_firstInsertSetsTenantAndNoPk() {
		EmsDemandConfigMapper mapper = mock(EmsDemandConfigMapper.class);
		when(mapper.selectOne(any(), anyBoolean())).thenReturn(null);
		EmsDemandConfigService svc = newService(mapper);

		EmsDemandConfig cfg = new EmsDemandConfig();
		cfg.setStationId(10L);
		cfg.setDemandLimitKw(new BigDecimal("1200.00"));
		svc.upsert(cfg);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<EmsDemandConfig> captor = ArgumentCaptor.forClass(EmsDemandConfig.class);
		verify(mapper).insert(captor.capture());
		verify(mapper, never()).updateById(any(EmsDemandConfig.class));
		assertNull(captor.getValue().getDemandConfigId());
		assertEquals(7L, captor.getValue().getTenantId());
	}

	@Test
	void upsert_resubmitUpdatesInPlace() {
		EmsDemandConfig existing = new EmsDemandConfig();
		existing.setDemandConfigId(1L);
		existing.setStationId(10L);
		EmsDemandConfigMapper mapper = mock(EmsDemandConfigMapper.class);
		when(mapper.selectOne(any(), anyBoolean())).thenReturn(existing);
		EmsDemandConfigService svc = newService(mapper);

		EmsDemandConfig cfg = new EmsDemandConfig();
		cfg.setStationId(10L);
		cfg.setDemandLimitKw(new BigDecimal("1500.00"));
		svc.upsert(cfg);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<EmsDemandConfig> upd = ArgumentCaptor.forClass(EmsDemandConfig.class);
		verify(mapper).updateById(upd.capture());
		assertEquals(1L, upd.getValue().getDemandConfigId()); // 原位更新，保留主键
		assertEquals(0, new BigDecimal("1500.00").compareTo(upd.getValue().getDemandLimitKw()));
	}

}
```

- [ ] **Step 6: 写 record service 测试**

`backend/energy-ems/src/test/java/com/energyx/ems/service/EmsDemandRecordServiceTest.java`：

```java
package com.energyx.ems.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.energyx.ems.entity.EmsDemandRecord;
import com.energyx.ems.mapper.EmsDemandRecordMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** EmsDemandRecordService 读写：查询按租户+站+窗过滤，upsert 透传 mapper。 */
class EmsDemandRecordServiceTest {

	@Test
	void getByStationAndWindow_returnsHit() {
		EmsDemandRecordMapper mapper = mock(EmsDemandRecordMapper.class);
		EmsDemandRecord hit = new EmsDemandRecord();
		hit.setWindowStart(LocalDateTime.of(2026, 8, 11, 10, 30));
		when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(hit);
		EmsDemandRecordService svc = new EmsDemandRecordService(mapper);

		EmsDemandRecord got = svc.getByStationAndWindow(7L, 10L, LocalDateTime.of(2026, 8, 11, 10, 30));
		assertSame(hit, got);
	}

	@Test
	void upsert_passesThroughToMapper() {
		EmsDemandRecordMapper mapper = mock(EmsDemandRecordMapper.class);
		EmsDemandRecordService svc = new EmsDemandRecordService(mapper);

		EmsDemandRecord rec = new EmsDemandRecord();
		rec.setTenantId(7L);
		rec.setStationId(10L);
		rec.setDemandKw(new BigDecimal("1200.00"));
		rec.setOverLimit(true);
		rec.setAction("SHED");
		EmsDemandRecord returned = svc.upsert(rec);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<EmsDemandRecord> captor = ArgumentCaptor.forClass(EmsDemandRecord.class);
		verify(mapper).upsert(captor.capture());
		assertSame(rec, returned);
		assertEquals("SHED", captor.getValue().getAction());
	}

}
```

- [ ] **Step 7: 运行测试验证通过**

Run: `mvn -pl energy-ems spring-javaformat:apply && mvn -pl energy-ems test`
Expected: PASS（5 条新测试绿 + 既有测试不回归）。

- [ ] **Step 8: 提交**

```bash
git add backend/energy-ems/src/main/resources/db/migration/V6__demand.sql \
  backend/energy-ems/src/main/java/com/energyx/ems/entity/EmsDemandConfig.java \
  backend/energy-ems/src/main/java/com/energyx/ems/entity/EmsDemandRecord.java \
  backend/energy-ems/src/main/java/com/energyx/ems/mapper/EmsDemandConfigMapper.java \
  backend/energy-ems/src/main/java/com/energyx/ems/mapper/EmsDemandRecordMapper.java \
  backend/energy-ems/src/main/java/com/energyx/ems/service/EmsDemandConfigService.java \
  backend/energy-ems/src/main/java/com/energyx/ems/service/EmsDemandRecordService.java \
  backend/energy-ems/src/test/java/com/energyx/ems/service/EmsDemandConfigServiceTest.java \
  backend/energy-ems/src/test/java/com/energyx/ems/service/EmsDemandRecordServiceTest.java
git commit -m "feat(ems): P1-2 需量数据模型（ems_demand_config/record + 实体 + 服务）"
```

---

### Task 2: METER 设备类型基建（产品种子 + 电表 Mapper + 前端 dict）

**Files:**
- Create: `backend/energy-product/src/main/resources/db/migration/V2__seed_meter.sql`
- Create: `backend/energy-ems/src/main/java/com/energyx/ems/model/MeterDevice.java`
- Create: `backend/energy-ems/src/main/java/com/energyx/ems/mapper/MeterDeviceMapper.java`
- Modify: `frontend/src/utils/dicts.ts:38-46`

**Interfaces:**
- Consumes: `PcsDevice` record（`com.energyx.ems.model`，字段 deviceId/tenantId/productKey/deviceName/status）、`PcsDeviceMapper` @Select 跨库模式、`V1__init_product.sql` 种子结构。
- Produces（后续任务依赖）：
  - `record MeterDevice(Long deviceId, Long tenantId, String productKey, String deviceName, Integer status)`
  - `MeterDeviceMapper.selectByStation(Long tenantId, Long stationId, String productKey) → List<MeterDevice>`

- [ ] **Step 1: 写 METER 产品种子**（仿 V1，product_id=2；**新迁移**才能让已存在库应用）

`backend/energy-product/src/main/resources/db/migration/V2__seed_meter.sql`：

```sql
-- =====================================================================
-- EnergyX 储能管理平台 · 产品域（energy-product 服务）
-- V2__seed_meter.sql —— 进线电能表产品种子（P1-2 需量管理功率数据源）
-- 版本：v1.5    日期：2026-08-11
-- 说明：新增 device_type='METER' 的 snd_ess_meter 产品，物模型属性 importPower（进线功率 kW）。
--       V1 已应用过（INSERT IGNORE 对已存在库不会再执行），故单独新迁移。
-- =====================================================================

INSERT IGNORE INTO `iot_product` (`product_id`, `tenant_id`, `category_id`, `product_key`, `product_name`, `device_type`, `auth_type`, `model_version`, `status`) VALUES
(2, 1, NULL, 'snd_ess_meter', '进线电能表', 'METER', 'SECRET', 'V1.0', 1);

INSERT IGNORE INTO `iot_thing_model` (`model_id`, `tenant_id`, `product_id`, `version`, `schema_json`, `status`, `is_current`) VALUES
(2, 1, 2, 'V1.0',
 '{"properties":[{"identifier":"importPower","name":"进线功率","dataType":"float","unit":"kW","accessMode":"r"}],"services":[],"events":[]}',
 1, 1);

INSERT IGNORE INTO `iot_thing_model_identifier` (`tenant_id`, `product_id`, `model_version`, `identifier`, `identifier_type`, `data_type`, `unit`, `required`) VALUES
(1, 2, 'V1.0', 'importPower', 1, 'float', 'kW', 1);
```

- [ ] **Step 2: 写电表 device record + Mapper**

`backend/energy-ems/src/main/java/com/energyx/ems/model/MeterDevice.java`：

```java
package com.energyx.ems.model;

/** 站内进线电能表（es_device.iot_device，device_type=METER，跨库只读投影）。 */
public record MeterDevice(Long deviceId, Long tenantId, String productKey, String deviceName, Integer status) {
}
```

`backend/energy-ems/src/main/java/com/energyx/ems/mapper/MeterDeviceMapper.java`（仿 `PcsDeviceMapper`）：

```java
package com.energyx.ems.mapper;

import com.energyx.ems.model.MeterDevice;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 站内进线电能表解析（跨库读 es_device.iot_device）。 */
@Mapper
public interface MeterDeviceMapper {

	@Select("""
			SELECT device_id, tenant_id, product_key, device_name, status
			FROM es_device.iot_device
			WHERE tenant_id = #{tenantId} AND station_id = #{stationId}
			  AND device_type = 'METER' AND product_key = #{productKey}
			  AND deleted = 0 AND status IN (2, 3)
			ORDER BY device_id
			""")
	List<MeterDevice> selectByStation(@Param("tenantId") Long tenantId, @Param("stationId") Long stationId,
			@Param("productKey") String productKey);

}
```

- [ ] **Step 3: 前端 dict 加 METER**

`frontend/src/utils/dicts.ts:38-46` 两处修改：

```ts
export const deviceTypeOptions = ['ENERGY_CABINET', 'BATTERY_CLUSTER', 'PCS', 'BMS', 'EMS', 'EDGE_GW', 'METER']
export const DEVICE_TYPE_TEXT: Record<string, string> = {
  ENERGY_CABINET: '储能柜',
  BATTERY_CLUSTER: '电池簇',
  PCS: '变流器 PCS',
  BMS: '电池管理系统 BMS',
  EMS: '能量管理系统 EMS',
  EDGE_GW: '边缘网关',
  METER: '进线电能表',
}
```

- [ ] **Step 4: 验证构建**

Run:
```
mvn -pl energy-product,energy-ems spring-javaformat:apply && mvn -pl energy-product,energy-ems compile
cd frontend && npx vue-tsc --noEmit
```
Expected: 三命令均通过（`device_type` 后端无枚举校验，新增 'METER' 是自由文本，无需改设备服务代码；产物是种子 + Mapper + dict）。

- [ ] **Step 5: 提交**

```bash
git add backend/energy-product/src/main/resources/db/migration/V2__seed_meter.sql \
  backend/energy-ems/src/main/java/com/energyx/ems/model/MeterDevice.java \
  backend/energy-ems/src/main/java/com/energyx/ems/mapper/MeterDeviceMapper.java \
  frontend/src/utils/dicts.ts
git commit -m "feat(ems): P1-2 METER 设备类型基建（snd_ess_meter 产品种子 + 电表 Mapper + 前端 dict）"
```

---

### Task 3: DemandDetector 纯函数

**Files:**
- Create: `backend/energy-ems/src/main/java/com/energyx/ems/util/DemandDetector.java`
- Test: `backend/energy-ems/src/test/java/com/energyx/ems/util/DemandDetectorTest.java`

**Interfaces:**
- Consumes: `TsdbClient.TelemetryRow(long ts, Double power, Integer runMode)`（`com.energyx.ems.service.TsdbClient`，ts 为 epoch ms）。
- Produces（后续任务依赖的精确签名）：
  - `DemandDetector.SLOT_MINUTES = 15`
  - `DemandDetector.slotStart(long ts) → LocalDateTime`、`slotStart(LocalDateTime) → LocalDateTime`、`slotEnd(LocalDateTime) → LocalDateTime`
  - `DemandDetector.slotAvg(List<TsdbClient.TelemetryRow>, LocalDateTime slotStart) → double`（槽位内非空 power 均值；无样本 → 0）
  - `DemandDetector.PCS_RATED_KW = 100.0`（单台 PCS 额定功率假设，简化 ΣPCS 可用功率）
  - `DemandDetector.detect(double slotAvgKw, double limitKw, int activePcsCount) → DetectResult`
  - `record DetectResult(double demandKw, double limitKw, boolean overLimit, double shaveKw, DemandAction action)`（record 组件访问器）
  - `enum DemandAction { NONE, SHED, ALARM_ONLY }`

- [ ] **Step 1: 写失败测试**

`backend/energy-ems/src/test/java/com/energyx/ems/util/DemandDetectorTest.java`：

```java
package com.energyx.ems.util;

import com.energyx.ems.service.TsdbClient;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** DemandDetector 纯函数：15min 槽位定位 / 槽位均值 / 超限判定 / 削峰功率钳制。 */
class DemandDetectorTest {

	private static long ms(LocalDateTime t) {
		return t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
	}

	private static TsdbClient.TelemetryRow row(int minute, double power) {
		return new TsdbClient.TelemetryRow(ms(LocalDateTime.of(2026, 8, 11, 10, minute)), power, null);
	}

	@Test
	void slotStart_truncatesTo15Min() {
		assertEquals(LocalDateTime.of(2026, 8, 11, 10, 30), DemandDetector.slotStart(LocalDateTime.of(2026, 8, 11, 10, 37)));
		assertEquals(LocalDateTime.of(2026, 8, 11, 10, 30), DemandDetector.slotStart(LocalDateTime.of(2026, 8, 11, 10, 30)));
		assertEquals(LocalDateTime.of(2026, 8, 11, 10, 45), DemandDetector.slotStart(LocalDateTime.of(2026, 8, 11, 10, 44)));
		assertEquals(LocalDateTime.of(2026, 8, 11, 0, 0), DemandDetector.slotStart(LocalDateTime.of(2026, 8, 11, 0, 7)));
	}

	@Test
	void slotStart_epochMsSameAsLocalDateTime() {
		LocalDateTime t = LocalDateTime.of(2026, 8, 11, 10, 37);
		assertEquals(DemandDetector.slotStart(t), DemandDetector.slotStart(ms(t)));
	}

	@Test
	void slotEnd_is15MinutesLater() {
		assertEquals(LocalDateTime.of(2026, 8, 11, 10, 45),
				DemandDetector.slotEnd(LocalDateTime.of(2026, 8, 11, 10, 30)));
	}

	@Test
	void slotAvg_averagesRowsInsideSlot() {
		LocalDateTime start = LocalDateTime.of(2026, 8, 11, 10, 30);
		List<TsdbClient.TelemetryRow> rows = List.of(row(30, 100), row(32, 120), row(40, 140));
		assertEquals(120.0, DemandDetector.slotAvg(rows, start));
	}

	@Test
	void slotAvg_excludesOutOfSlotAndNullPower() {
		LocalDateTime start = LocalDateTime.of(2026, 8, 11, 10, 30);
		List<TsdbClient.TelemetryRow> rows = List.of(
				row(29, 1000),  // 槽位外（10:30 前）
				row(45, 1000),  // 槽位外（10:45 起）
				row(31, 200),
				new TsdbClient.TelemetryRow(ms(LocalDateTime.of(2026, 8, 11, 10, 33)), null, null), // 空功率跳过
				row(35, 400));
		assertEquals(300.0, DemandDetector.slotAvg(rows, start));
	}

	@Test
	void slotAvg_emptyReturnsZero() {
		assertEquals(0.0, DemandDetector.slotAvg(List.of(), LocalDateTime.of(2026, 8, 11, 10, 30)));
	}

	@Test
	void detect_overWithPcsShedsClampedToAvailable() {
		DemandDetector.DetectResult r = DemandDetector.detect(500, 100, 2); // 需 400，可用 2×100=200
		assertTrue(r.overLimit());
		assertEquals(200.0, r.shaveKw());
		assertEquals(DemandDetector.DemandAction.SHED, r.action());
	}

	@Test
	void detect_overWithPcsShaveLessThanNeeded() {
		DemandDetector.DetectResult r = DemandDetector.detect(150, 100, 2); // 需 50 < 可用 200
		assertTrue(r.overLimit());
		assertEquals(50.0, r.shaveKw());
		assertEquals(DemandDetector.DemandAction.SHED, r.action());
	}

	@Test
	void detect_noPcsAlarmOnly() {
		DemandDetector.DetectResult r = DemandDetector.detect(500, 100, 0);
		assertTrue(r.overLimit());
		assertEquals(0.0, r.shaveKw());
		assertEquals(DemandDetector.DemandAction.ALARM_ONLY, r.action());
	}

	@Test
	void detect_notOverNone() {
		DemandDetector.DetectResult r = DemandDetector.detect(80, 100, 2);
		assertFalse(r.overLimit());
		assertEquals(0.0, r.shaveKw());
		assertEquals(DemandDetector.DemandAction.NONE, r.action());
	}

	@Test
	void detect_atLimitNotOver() {
		// 严格大于才判超限
		DemandDetector.DetectResult r = DemandDetector.detect(100, 100, 2);
		assertFalse(r.overLimit());
		assertEquals(DemandDetector.DemandAction.NONE, r.action());
	}

	@Test
	void detect_nonPositiveLimitNeverOver() {
		DemandDetector.DetectResult r = DemandDetector.detect(500, 0, 2);
		assertFalse(r.overLimit());
		assertEquals(DemandDetector.DemandAction.NONE, r.action());
	}

}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `mvn -pl energy-ems test -Dtest=DemandDetectorTest`
Expected: 编译失败（DemandDetector 不存在）或全 FAIL。

- [ ] **Step 3: 实现 DemandDetector**

`backend/energy-ems/src/main/java/com/energyx/ems/util/DemandDetector.java`：

```java
package com.energyx.ems.util;

import com.energyx.ems.service.TsdbClient;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 需量检测纯函数（P1-2）：固定 15min 槽位定位、槽位均值、超限判定、削峰功率钳制。
 *
 * <p>
 * 槽位语义：00:00–00:15 … 共 96 槽/天（固定槽位，非滑动窗口），与工商业需量计费口径一致。 检测值取槽位内当前已积累样本均值
 * （槽位中途即可越限触发早期预警）。
 * </p>
 */
public final class DemandDetector {

	/** 槽位时长（分钟）。 */
	public static final int SLOT_MINUTES = 15;

	/** 单台 PCS 额定功率假设（kW）。ΣPCS 可用功率 = 活跃 PCS 数 × 该值（简化；SOC 深放保护由 socTarget 兜底）。 */
	public static final double PCS_RATED_KW = 100.0;

	private DemandDetector() {
	}

	/** ts（epoch ms）→ 所属 15min 槽位起点。 */
	public static LocalDateTime slotStart(long ts) {
		return slotStart(LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneId.systemDefault()));
	}

	/** 时刻向下取整到 15min 槽位起点。 */
	public static LocalDateTime slotStart(LocalDateTime dt) {
		return dt.withSecond(0).withNano(0).minusMinutes(dt.getMinute() % SLOT_MINUTES);
	}

	/** 槽位终点（起点 + 15min）。 */
	public static LocalDateTime slotEnd(LocalDateTime start) {
		return start.plusMinutes(SLOT_MINUTES);
	}

	/** 槽位均值：rows 中落在 [start, start+15min) 且 power 非空样本的均值；无样本返回 0。 */
	public static double slotAvg(List<TsdbClient.TelemetryRow> rows, LocalDateTime start) {
		long startMs = start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
		long endMs = slotEnd(start).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
		double sum = 0;
		int n = 0;
		for (TsdbClient.TelemetryRow r : rows) {
			if (r.ts() >= startMs && r.ts() < endMs && r.power() != null) {
				sum += r.power();
				n++;
			}
		}
		return n == 0 ? 0 : sum / n;
	}

	/** 需量动作。 */
	public enum DemandAction {
		/** 未超限，不动作 */
		NONE,
		/** 超限且有活跃 PCS，下发削峰 */
		SHED,
		/** 超限但无活跃 PCS，只告警不削峰 */
		ALARM_ONLY
	}

	/** 检测结果。 */
	public record DetectResult(double demandKw, double limitKw, boolean overLimit, double shaveKw,
			DemandAction action) {
	}

	/**
	 * 超限判定 + 削峰功率计算。
	 *
	 * <p>
	 * 超限（均值 &gt; 限值）且活跃 PCS 数 &gt; 0 → 削峰功率 = min(超限量, 活跃 PCS 数 × PCS_RATED_KW)； 超限但无 PCS →
	 * ALARM_ONLY（削峰 0）；未超限或限值 ≤ 0 → NONE。
	 * </p>
	 */
	public static DetectResult detect(double slotAvgKw, double limitKw, int activePcsCount) {
		if (limitKw <= 0) {
			return new DetectResult(slotAvgKw, limitKw, false, 0, DemandAction.NONE);
		}
		boolean over = slotAvgKw > limitKw;
		if (!over) {
			return new DetectResult(slotAvgKw, limitKw, false, 0, DemandAction.NONE);
		}
		if (activePcsCount <= 0) {
			return new DetectResult(slotAvgKw, limitKw, true, 0, DemandAction.ALARM_ONLY);
		}
		double shave = Math.min(slotAvgKw - limitKw, activePcsCount * PCS_RATED_KW);
		return new DetectResult(slotAvgKw, limitKw, true, shave, DemandAction.SHED);
	}

}
```

- [ ] **Step 4: 运行测试验证通过**

Run: `mvn -pl energy-ems spring-javaformat:apply && mvn -pl energy-ems test -Dtest=DemandDetectorTest`
Expected: PASS（11 条）。

- [ ] **Step 5: 提交**

```bash
git add backend/energy-ems/src/main/java/com/energyx/ems/util/DemandDetector.java \
  backend/energy-ems/src/test/java/com/energyx/ems/util/DemandDetectorTest.java
git commit -m "feat(ems): P1-2 DemandDetector 纯函数（15min 槽位均值/超限判定/削峰钳制）"
```

---

### Task 4: DemandShaveClient 削峰下发封装

**Files:**
- Create: `backend/energy-ems/src/main/java/com/energyx/ems/service/DemandShaveClient.java`
- Test: `backend/energy-ems/src/test/java/com/energyx/ems/service/DemandShaveClientTest.java`

**Interfaces:**
- Consumes: `CommandClient.dispatch(String productKey, String deviceName, String command, Map<String,Object> params, long createBy) → String`（业务失败抛 `BusinessException`）、`ShadowClient.reportedSoc(long deviceId) → Optional<Double>`、`EmsDemandRecordService.upsert(EmsDemandRecord) → EmsDemandRecord`（Task 1）、`PcsDevice` record、Task 1 的 `EmsDemandConfig`/`EmsDemandRecord`。
- Produces（后续任务依赖）：
  - `DemandShaveClient.shave(EmsDemandConfig config, List<PcsDevice> devices, LocalDateTime windowStart, LocalDateTime windowEnd, double demandKw, double limitKw, double shaveKw) → EmsDemandRecord`

- [ ] **Step 1: 写失败测试**

`backend/energy-ems/src/test/java/com/energyx/ems/service/DemandShaveClientTest.java`：

```java
package com.energyx.ems.service;

import com.energyx.ems.entity.EmsDemandConfig;
import com.energyx.ems.entity.EmsDemandRecord;
import com.energyx.ems.model.PcsDevice;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** DemandShaveClient 削峰下发：均分功率、socTarget 取影子、失败留痕 SHED_FAILED、无 PCS 留 ALARM_ONLY。 */
class DemandShaveClientTest {

	private static EmsDemandConfig config() {
		EmsDemandConfig cfg = new EmsDemandConfig();
		cfg.setTenantId(7L);
		cfg.setStationId(10L);
		cfg.setDemandLimitKw(new BigDecimal("100.00"));
		cfg.setDemandRate(new BigDecimal("40.0000"));
		return cfg;
	}

	private static PcsDevice pcs(long id, String name) {
		return new PcsDevice(id, 7L, "snd_ess_pcs", name, 3);
	}

	private static LocalDateTime win() {
		return LocalDateTime.of(2026, 8, 11, 10, 30);
	}

	@Test
	void shave_dispatchesEachPcsEqualShare() {
		CommandClient commandClient = mock(CommandClient.class);
		ShadowClient shadowClient = mock(ShadowClient.class);
		when(shadowClient.reportedSoc(1L)).thenReturn(Optional.of(60.0));
		when(shadowClient.reportedSoc(2L)).thenReturn(Optional.empty()); // 回退 30
		EmsDemandRecordService recordService = mock(EmsDemandRecordService.class);
		when(recordService.upsert(any(EmsDemandRecord.class))).thenAnswer(inv -> inv.getArgument(0));
		DemandShaveClient client = new DemandShaveClient(commandClient, shadowClient, recordService);

		EmsDemandRecord rec = client.shave(config(), List.of(pcs(1L, "p1"), pcs(2L, "p2")), win(), win().plusMinutes(15),
				500, 100, 200);

		verify(commandClient, times(2)).dispatch(eq("snd_ess_pcs"), anyString(), eq("DISCHARGE"), any(), eq(0L));
		verify(commandClient).dispatch(eq("snd_ess_pcs"), eq("p1"), eq("DISCHARGE"), argThat(p -> {
			return p.get("action").equals("DISCHARGE") && ((Number) p.get("power")).doubleValue() == 100.0
					&& ((Number) p.get("socTarget")).doubleValue() == 60.0;
		}), eq(0L));
		verify(commandClient).dispatch(eq("snd_ess_pcs"), eq("p2"), eq("DISCHARGE"), argThat(p -> {
			return ((Number) p.get("socTarget")).doubleValue() == 30.0;
		}), eq(0L));
		assertEquals("SHED", rec.getAction());
		assertTrue(rec.getOverLimit());
		assertEquals(0, new BigDecimal("200.00").compareTo(rec.getShavedKw()));
	}

	@Test
	void shave_noDevicesRecordsAlarmOnly() {
		CommandClient commandClient = mock(CommandClient.class);
		EmsDemandRecordService recordService = mock(EmsDemandRecordService.class);
		when(recordService.upsert(any(EmsDemandRecord.class))).thenAnswer(inv -> inv.getArgument(0));
		DemandShaveClient client = new DemandShaveClient(commandClient, mock(ShadowClient.class), recordService);

		EmsDemandRecord rec = client.shave(config(), List.of(), win(), win().plusMinutes(15), 500, 100, 200);

		verify(commandClient, never()).dispatch(anyString(), anyString(), anyString(), any(), anyLong());
		assertEquals("ALARM_ONLY", rec.getAction());
		assertEquals(0, new BigDecimal("0.00").compareTo(rec.getShavedKw()));
	}

	@Test
	void shave_dispatchFailureRecordsShavedFailed() {
		CommandClient commandClient = mock(CommandClient.class);
		when(commandClient.dispatch(eq("snd_ess_pcs"), eq("p1"), anyString(), any(), eq(0L)))
				.thenThrow(new RuntimeException("device offline"));
		EmsDemandRecordService recordService = mock(EmsDemandRecordService.class);
		when(recordService.upsert(any(EmsDemandRecord.class))).thenAnswer(inv -> inv.getArgument(0));
		DemandShaveClient client = new DemandShaveClient(commandClient, mock(ShadowClient.class), recordService);

		EmsDemandRecord rec = client.shave(config(), List.of(pcs(1L, "p1")), win(), win().plusMinutes(15), 500, 100, 200);

		assertEquals("SHED_FAILED", rec.getAction()); // 异常被捕获，不中断
		assertEquals(0, new BigDecimal("200.00").compareTo(rec.getShavedKw())); // 保留意图削峰功率
	}

	@Test
	void shave_nullDevicesRecordsAlarmOnly() {
		CommandClient commandClient = mock(CommandClient.class);
		EmsDemandRecordService recordService = mock(EmsDemandRecordService.class);
		when(recordService.upsert(any(EmsDemandRecord.class))).thenAnswer(inv -> inv.getArgument(0));
		DemandShaveClient client = new DemandShaveClient(commandClient, mock(ShadowClient.class), recordService);

		EmsDemandRecord rec = client.shave(config(), null, win(), win().plusMinutes(15), 500, 100, 200);

		assertEquals("ALARM_ONLY", rec.getAction());
	}

}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `mvn -pl energy-ems test -Dtest=DemandShaveClientTest`
Expected: 编译失败（DemandShaveClient 不存在）。

- [ ] **Step 3: 实现 DemandShaveClient**

`backend/energy-ems/src/main/java/com/energyx/ems/service/DemandShaveClient.java`：

```java
package com.energyx.ems.service;

import com.energyx.ems.entity.EmsDemandConfig;
import com.energyx.ems.entity.EmsDemandRecord;
import com.energyx.ems.model.PcsDevice;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 需量削峰下发封装（P1-2）：向站内活跃 PCS 均分削峰功率下 DISCHARGE，并按槽位留痕。
 *
 * <p>
 * 无活跃 PCS → 仅记录 ALARM_ONLY（不削峰）；任一 PCS 下发失败 → action=SHED_FAILED（不中断，shaved_kw 保留意图值）。
 * </p>
 */
@Slf4j
@Component
public class DemandShaveClient {

	private final CommandClient commandClient;

	private final ShadowClient shadowClient;

	private final EmsDemandRecordService recordService;

	public DemandShaveClient(CommandClient commandClient, ShadowClient shadowClient,
			EmsDemandRecordService recordService) {
		this.commandClient = commandClient;
		this.shadowClient = shadowClient;
		this.recordService = recordService;
	}

	/**
	 * 削峰下发 + 槽位留痕（upsert 幂等）。
	 *
	 * @param devices 站内活跃 PCS（调度器已解析，避免二次查询）
	 * @param shaveKw 削峰功率（DemandDetector 已按可用功率钳制）
	 */
	public EmsDemandRecord shave(EmsDemandConfig config, List<PcsDevice> devices, LocalDateTime windowStart,
			LocalDateTime windowEnd, double demandKw, double limitKw, double shaveKw) {
		if (devices == null || devices.isEmpty()) {
			return recordService
				.upsert(newRecord(config, windowStart, windowEnd, demandKw, limitKw, true, 0, "ALARM_ONLY"));
		}
		double share = round2(shaveKw / devices.size());
		boolean anyFailed = false;
		for (PcsDevice dev : devices) {
			try {
				dispatchShave(dev, share, windowStart);
			}
			catch (Exception e) {
				log.warn("[DemandShave] 削峰下发失败 deviceId={} msg={}", dev.deviceId(), e.getMessage());
				anyFailed = true;
			}
		}
		String action = anyFailed ? "SHED_FAILED" : "SHED";
		return recordService.upsert(newRecord(config, windowStart, windowEnd, demandKw, limitKw, true, shaveKw, action));
	}

	private void dispatchShave(PcsDevice dev, double share, LocalDateTime windowStart) {
		Map<String, Object> params = new HashMap<>();
		params.put("action", "DISCHARGE");
		params.put("power", share);
		params.put("socTarget", socTargetOf(dev));
		params.put("time", windowStart.toString());
		commandClient.dispatch(dev.productKey(), dev.deviceName(), "DISCHARGE", params, 0L);
	}

	/** 放停下限：影子实时 SOC 兜底，无则回退 30（防深放）。 */
	private double socTargetOf(PcsDevice dev) {
		return shadowClient.reportedSoc(dev.deviceId()).orElse(30.0);
	}

	private static EmsDemandRecord newRecord(EmsDemandConfig config, LocalDateTime windowStart,
			LocalDateTime windowEnd, double demandKw, double limitKw, boolean overLimit, double shaveKw,
			String action) {
		EmsDemandRecord rec = new EmsDemandRecord();
		rec.setTenantId(config.getTenantId());
		rec.setStationId(config.getStationId());
		rec.setWindowStart(windowStart);
		rec.setWindowEnd(windowEnd);
		rec.setDemandKw(BigDecimal.valueOf(round2(demandKw)));
		rec.setLimitKw(BigDecimal.valueOf(round2(limitKw)));
		rec.setOverLimit(overLimit);
		rec.setShavedKw(BigDecimal.valueOf(round2(shaveKw)));
		rec.setAction(action);
		return rec;
	}

	private static double round2(double v) {
		return Math.round(v * 100.0) / 100.0;
	}

}
```

- [ ] **Step 4: 运行测试验证通过**

Run: `mvn -pl energy-ems spring-javaformat:apply && mvn -pl energy-ems test -Dtest=DemandShaveClientTest`
Expected: PASS（4 条）。

- [ ] **Step 5: 提交**

```bash
git add backend/energy-ems/src/main/java/com/energyx/ems/service/DemandShaveClient.java \
  backend/energy-ems/src/test/java/com/energyx/ems/service/DemandShaveClientTest.java
git commit -m "feat(ems): P1-2 DemandShaveClient 削峰下发封装（均分/socTarget/失败留痕）"
```

---

### Task 5: DemandDetectScheduler + DemandAlarmProducer（检测循环编排 + 告警发布）

**Files:**
- Create: `backend/energy-ems/src/main/java/com/energyx/ems/service/DemandAlarmProducer.java`
- Create: `backend/energy-ems/src/main/java/com/energyx/ems/scheduler/DemandDetectScheduler.java`
- Test: `backend/energy-ems/src/test/java/com/energyx/ems/scheduler/DemandDetectSchedulerTest.java`

**Interfaces:**
- Consumes: `EmsDemandConfigService.listAll()`、`EmsDemandRecordService.*`（Task 1）、`MeterDeviceMapper.selectByStation`（Task 2）、`DemandDetector`（Task 3，真实静态调用，不在测试中 mockStatic）、`DemandShaveClient.shave`（Task 4）、`TsdbClient.history(long, String, LocalDate) → List<TelemetryRow>`、`PcsDeviceMapper.selectByStation(Long, Long, String)`、`DistributedLock.runIfAcquired(String key, long ttlSeconds, Runnable)`、`EmsKafkaProducer.send(String topic, String key, String value)`、`KafkaTopicConstant.IOT_THING_EVENT`（`com.energyx.common.constant`）、`ThingEventMessage`（`com.energyx.common.message`）、`CommandClient.dispatch` 抛 `BusinessException`。
- Produces（后续任务依赖）：
  - `DemandAlarmProducer.publishDemandOverLimit(EmsDemandConfig config, MeterDevice meter, double demandKw, double limitKw, LocalDateTime windowStart) → void`
  - `DemandDetectScheduler.detect()`（`@Scheduled(cron = "0 * * * * *")`，锁 key `scheduled:ems-demand-detect`）

- [ ] **Step 1: 写失败测试**

`backend/energy-ems/src/test/java/com/energyx/ems/scheduler/DemandDetectSchedulerTest.java`：

```java
package com.energyx.ems.scheduler;

import com.energyx.common.redis.DistributedLock;
import com.energyx.ems.entity.EmsDemandConfig;
import com.energyx.ems.entity.EmsDemandRecord;
import com.energyx.ems.mapper.MeterDeviceMapper;
import com.energyx.ems.mapper.PcsDeviceMapper;
import com.energyx.ems.model.MeterDevice;
import com.energyx.ems.model.PcsDevice;
import com.energyx.ems.service.DemandAlarmProducer;
import com.energyx.ems.service.DemandShaveClient;
import com.energyx.ems.service.EmsDemandConfigService;
import com.energyx.ems.service.EmsDemandRecordService;
import com.energyx.ems.service.TsdbClient;
import com.energyx.ems.util.DemandDetector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** DemandDetectScheduler 编排：超限削峰+首超告警、槽位定型防重复告警、未超限写 NONE、缺电表/遥测/限值跳过。 */
class DemandDetectSchedulerTest {

	private EmsDemandConfigService configService;
	private EmsDemandRecordService recordService;
	private MeterDeviceMapper meterDeviceMapper;
	private PcsDeviceMapper pcsDeviceMapper;
	private TsdbClient tsdbClient;
	private DemandShaveClient shaveClient;
	private DemandAlarmProducer alarmProducer;
	private DistributedLock distributedLock;
	private DemandDetectScheduler scheduler;

	@BeforeEach
	void setup() {
		configService = mock(EmsDemandConfigService.class);
		recordService = mock(EmsDemandRecordService.class);
		meterDeviceMapper = mock(MeterDeviceMapper.class);
		pcsDeviceMapper = mock(PcsDeviceMapper.class);
		tsdbClient = mock(TsdbClient.class);
		shaveClient = mock(DemandShaveClient.class);
		alarmProducer = mock(DemandAlarmProducer.class);
		distributedLock = mock(DistributedLock.class);
		when(distributedLock.runIfAcquired(anyString(), anyLong(), any(Runnable.class))).thenAnswer(inv -> {
			((Runnable) inv.getArgument(2)).run();
			return true;
		});
		scheduler = new DemandDetectScheduler(configService, recordService, meterDeviceMapper, pcsDeviceMapper,
				tsdbClient, shaveClient, alarmProducer, distributedLock);
		ReflectionTestUtils.setField(scheduler, "meterProductKey", "snd_ess_meter");
		ReflectionTestUtils.setField(scheduler, "pcsProductKey", "snd_ess_pcs");
	}

	private static EmsDemandConfig config(double limitKw) {
		EmsDemandConfig cfg = new EmsDemandConfig();
		cfg.setTenantId(7L);
		cfg.setStationId(10L);
		cfg.setDemandLimitKw(new BigDecimal(limitKw));
		cfg.setDemandRate(new BigDecimal("40.0000"));
		return cfg;
	}

	/** 造一条落在当前 15min 槽位内的 power 遥测（真实 DemandDetector 会算出该均值）。 */
	private static TsdbClient.TelemetryRow rowNow(double power) {
		LocalDateTime slotStart = DemandDetector.slotStart(LocalDateTime.now());
		long ts = slotStart.plusSeconds(30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
		return new TsdbClient.TelemetryRow(ts, power, null);
	}

	private void stubMeterAndRows(double power) {
		when(meterDeviceMapper.selectByStation(7L, 10L, "snd_ess_meter"))
				.thenReturn(List.of(new MeterDevice(1L, 7L, "snd_ess_meter", "m1", 3)));
		when(tsdbClient.history(anyLong(), anyString(), any(LocalDate.class)))
				.thenReturn(List.of(rowNow(power)));
	}

	@Test
	void detect_overLimitShavesAndPublishesAlarmOnce() {
		when(configService.listAll()).thenReturn(List.of(config(10)));
		stubMeterAndRows(500);
		when(pcsDeviceMapper.selectByStation(7L, 10L, "snd_ess_pcs")).thenReturn(List.of(new PcsDevice(2L, 7L, "snd_ess_pcs", "p1", 3)));
		when(recordService.getByStationAndWindow(anyLong(), anyLong(), any(LocalDateTime.class))).thenReturn(null);
		when(shaveClient.shave(any(), anyList(), any(), any(), anyDouble(), anyDouble(), anyDouble()))
				.thenAnswer(inv -> {
					EmsDemandRecord r = new EmsDemandRecord();
					r.setOverLimit(true);
					r.setAction("SHED");
					return r;
				});

		scheduler.detect();

		verify(shaveClient).shave(eq(config(10)), any(), any(), any(), eq(500.0), eq(10.0), anyDouble());
		verify(alarmProducer, times(1)).publishDemandOverLimit(any(), any(), anyDouble(), anyDouble(), any());
		verify(recordService, never()).upsert(argThat(r -> !Boolean.TRUE.equals(r.getOverLimit())));
	}

	@Test
	void detect_alreadyOverSkipsAlarm() {
		when(configService.listAll()).thenReturn(List.of(config(10)));
		stubMeterAndRows(500);
		when(pcsDeviceMapper.selectByStation(7L, 10L, "snd_ess_pcs")).thenReturn(List.of(new PcsDevice(2L, 7L, "snd_ess_pcs", "p1", 3)));
		EmsDemandRecord existing = new EmsDemandRecord();
		existing.setOverLimit(true); // 槽位已定型超限
		when(recordService.getByStationAndWindow(anyLong(), anyLong(), any(LocalDateTime.class))).thenReturn(existing);

		scheduler.detect();

		verify(alarmProducer, never()).publishDemandOverLimit(any(), any(), anyDouble(), anyDouble(), any());
		verify(shaveClient, times(1)).shave(any(), any(), any(), any(), anyDouble(), anyDouble(), anyDouble());
	}

	@Test
	void detect_notOverUpsertsNoneRecord() {
		when(configService.listAll()).thenReturn(List.of(config(10)));
		stubMeterAndRows(5); // 均值 5 < 限值 10
		when(recordService.getByStationAndWindow(anyLong(), anyLong(), any(LocalDateTime.class))).thenReturn(null);

		scheduler.detect();

		verify(shaveClient, never()).shave(any(), any(), any(), any(), anyDouble(), anyDouble(), anyDouble());
		verify(alarmProducer, never()).publishDemandOverLimit(any(), any(), anyDouble(), anyDouble(), any());
		verify(recordService).upsert(argThat(r -> !Boolean.TRUE.equals(r.getOverLimit()) && "NONE".equals(r.getAction())));
	}

	@Test
	void detect_meterMissingSkips() {
		when(configService.listAll()).thenReturn(List.of(config(10)));
		when(meterDeviceMapper.selectByStation(7L, 10L, "snd_ess_meter")).thenReturn(List.of());

		scheduler.detect();

		verify(tsdbClient, never()).history(anyLong(), anyString(), any());
		verify(recordService, never()).upsert(any());
	}

	@Test
	void detect_tsdbEmptySkips() {
		when(configService.listAll()).thenReturn(List.of(config(10)));
		when(meterDeviceMapper.selectByStation(7L, 10L, "snd_ess_meter"))
				.thenReturn(List.of(new MeterDevice(1L, 7L, "snd_ess_meter", "m1", 3)));
		when(tsdbClient.history(anyLong(), anyString(), any(LocalDate.class))).thenReturn(List.of());

		scheduler.detect();

		verify(recordService, never()).upsert(any());
		verify(alarmProducer, never()).publishDemandOverLimit(any(), any(), anyDouble(), anyDouble(), any());
	}

	@Test
	void detect_noLimitSkips() {
		EmsDemandConfig cfg = config(0); // 限值 ≤ 0
		when(configService.listAll()).thenReturn(List.of(cfg));
		stubMeterAndRows(500);

		scheduler.detect();

		verify(recordService, never()).upsert(any());
		verify(shaveClient, never()).shave(any(), any(), any(), any(), anyDouble(), anyDouble(), anyDouble());
	}

	@Test
	void detect_oneStationFailureDoesNotStopLoop() {
		EmsDemandConfig bad = config(10);
		when(configService.listAll()).thenReturn(List.of(bad, config(20)));
		// 第一站电表查询抛异常
		when(meterDeviceMapper.selectByStation(7L, 10L, "snd_ess_meter")).thenThrow(new RuntimeException("db down"));
		stubMeterAndRows(5); // 第二站正常（站 id 20）
		when(meterDeviceMapper.selectByStation(7L, 20L, "snd_ess_meter"))
				.thenReturn(List.of(new MeterDevice(3L, 7L, "snd_ess_meter", "m3", 3)));
		when(recordService.getByStationAndWindow(anyLong(), anyLong(), any(LocalDateTime.class))).thenReturn(null);

		scheduler.detect();

		verify(recordService, times(1)).upsert(any(EmsDemandRecord.class)); // 第二站仍执行
	}

}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `mvn -pl energy-ems test -Dtest=DemandDetectSchedulerTest`
Expected: 编译失败（两个类不存在）。

- [ ] **Step 3: 实现 DemandAlarmProducer**

`backend/energy-ems/src/main/java/com/energyx/ems/service/DemandAlarmProducer.java`：

```java
package com.energyx.ems.service;

import com.energyx.common.constant.KafkaTopicConstant;
import com.energyx.common.message.ThingEventMessage;
import com.energyx.ems.entity.EmsDemandConfig;
import com.energyx.ems.model.MeterDevice;
import com.energyx.ems.mqtt.EmsKafkaProducer;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** 需量超限事件发布（P1-2）：同一槽位首超时发一次 iot-thing-event(demandOverLimit)。 */
@Slf4j
@Component
public class DemandAlarmProducer {

	private static final ObjectMapper JSON = new ObjectMapper();

	private final EmsKafkaProducer kafkaProducer;

	public DemandAlarmProducer(EmsKafkaProducer kafkaProducer) {
		this.kafkaProducer = kafkaProducer;
	}

	/** 发布需量超限事件（messageId 按站+槽位幂等；发布失败仅 log，不抛）。 */
	public void publishDemandOverLimit(EmsDemandConfig config, MeterDevice meter, double demandKw, double limitKw,
			LocalDateTime windowStart) {
		ThingEventMessage evt = new ThingEventMessage();
		evt.setMessageId("demand-" + config.getStationId() + "-" + windowStart);
		evt.setEventId(evt.getMessageId());
		evt.setDeviceId(meter.deviceId());
		evt.setTenantId(config.getTenantId());
		evt.setStationId(config.getStationId());
		evt.setProductKey(meter.productKey());
		evt.setEventName("demandOverLimit");
		evt.setSeverity(severityOf(demandKw, limitKw));
		evt.setCode("DEMAND_OVER_LIMIT");
		evt.setTs(System.currentTimeMillis());
		evt.getData().put("demandKw", demandKw);
		evt.getData().put("limitKw", limitKw);
		evt.getData().put("stationId", config.getStationId());
		try {
			kafkaProducer.send(KafkaTopicConstant.IOT_THING_EVENT, String.valueOf(meter.deviceId()),
					JSON.writeValueAsString(evt));
		}
		catch (Exception e) {
			log.warn("[DemandAlarm] 需量超限事件发布失败 stationId={} msg={}", config.getStationId(), e.getMessage());
		}
	}

	/** 超限比例 ≥1.2 严重(3)，否则一般(2)。 */
	private static int severityOf(double demandKw, double limitKw) {
		return limitKw > 0 && demandKw / limitKw >= 1.2 ? 3 : 2;
	}

}
```

- [ ] **Step 4: 实现 DemandDetectScheduler**

`backend/energy-ems/src/main/java/com/energyx/ems/scheduler/DemandDetectScheduler.java`：

```java
package com.energyx.ems.scheduler;

import com.energyx.common.redis.DistributedLock;
import com.energyx.ems.entity.EmsDemandConfig;
import com.energyx.ems.entity.EmsDemandRecord;
import com.energyx.ems.mapper.MeterDeviceMapper;
import com.energyx.ems.mapper.PcsDeviceMapper;
import com.energyx.ems.model.MeterDevice;
import com.energyx.ems.model.PcsDevice;
import com.energyx.ems.service.DemandAlarmProducer;
import com.energyx.ems.service.DemandShaveClient;
import com.energyx.ems.service.EmsDemandConfigService;
import com.energyx.ems.service.EmsDemandRecordService;
import com.energyx.ems.service.TsdbClient;
import com.energyx.ems.util.DemandDetector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 需量检测调度器（P1-2）：每分钟遍历有需量配置的站，检测当前 15min 槽位，超限即削峰下发 + 留痕 + 告警。
 *
 * <p>
 * 调度线程无租户上下文（TenantContext 仅 HTTP 线程生效），租户 id 一律取配置行；单站失败仅 log 不中断循环。
 * </p>
 */
@Slf4j
@Component
public class DemandDetectScheduler {

	private final EmsDemandConfigService configService;

	private final EmsDemandRecordService recordService;

	private final MeterDeviceMapper meterDeviceMapper;

	private final PcsDeviceMapper pcsDeviceMapper;

	private final TsdbClient tsdbClient;

	private final DemandShaveClient shaveClient;

	private final DemandAlarmProducer alarmProducer;

	private final DistributedLock distributedLock;

	@Value("${energyx.ems.meter-product-key:snd_ess_meter}")
	private String meterProductKey;

	@Value("${energyx.ems.product-key:snd_ess_pcs}")
	private String pcsProductKey;

	public DemandDetectScheduler(EmsDemandConfigService configService, EmsDemandRecordService recordService,
			MeterDeviceMapper meterDeviceMapper, PcsDeviceMapper pcsDeviceMapper, TsdbClient tsdbClient,
			DemandShaveClient shaveClient, DemandAlarmProducer alarmProducer, DistributedLock distributedLock) {
		this.configService = configService;
		this.recordService = recordService;
		this.meterDeviceMapper = meterDeviceMapper;
		this.pcsDeviceMapper = pcsDeviceMapper;
		this.tsdbClient = tsdbClient;
		this.shaveClient = shaveClient;
		this.alarmProducer = alarmProducer;
		this.distributedLock = distributedLock;
	}

	/** 每分钟触发：全量配置站检测。锁 TTL 60s 覆盖最坏遍历耗时。 */
	@Scheduled(cron = "0 * * * * *")
	public void detect() {
		distributedLock.runIfAcquired("scheduled:ems-demand-detect", 60, this::doDetect);
	}

	private void doDetect() {
		LocalDate today = LocalDate.now();
		for (EmsDemandConfig config : configService.listAll()) {
			try {
				detectOne(config, today);
			}
			catch (Exception e) {
				log.warn("[DemandDetect] 站点检测失败 stationId={} msg={}", config.getStationId(), e.getMessage());
			}
		}
	}

	private void detectOne(EmsDemandConfig config, LocalDate today) {
		if (config.getDemandLimitKw() == null || config.getDemandLimitKw().signum() <= 0) {
			log.warn("[DemandDetect] 站点未配置需量限值，跳过 stationId={}", config.getStationId());
			return;
		}
		List<MeterDevice> meters = meterDeviceMapper.selectByStation(config.getTenantId(), config.getStationId(),
				meterProductKey);
		if (meters == null || meters.isEmpty()) {
			log.warn("[DemandDetect] 站点无电表，跳过 stationId={}", config.getStationId());
			return;
		}
		MeterDevice meter = meters.get(0);
		List<TsdbClient.TelemetryRow> rows = tsdbClient.history(meter.deviceId(), meter.productKey(), today);
		if (rows == null || rows.isEmpty()) {
			log.warn("[DemandDetect] 电表无遥测，跳过 stationId={} deviceId={}", config.getStationId(), meter.deviceId());
			return;
		}
		List<PcsDevice> pcs = pcsDeviceMapper.selectByStation(config.getTenantId(), config.getStationId(),
				pcsProductKey);
		LocalDateTime now = LocalDateTime.now();
		LocalDateTime slotStart = DemandDetector.slotStart(now);
		LocalDateTime slotEnd = DemandDetector.slotEnd(slotStart);
		double avg = DemandDetector.slotAvg(rows, slotStart);
		double limit = num(config.getDemandLimitKw());
		DemandDetector.DetectResult result = DemandDetector.detect(avg, limit, pcs == null ? 0 : pcs.size());

		EmsDemandRecord existing = recordService.getByStationAndWindow(config.getTenantId(), config.getStationId(),
				slotStart);
		boolean wasOver = existing != null && Boolean.TRUE.equals(existing.getOverLimit());
		if (result.overLimit()) {
			shaveClient.shave(config, pcs, slotStart, slotEnd, result.demandKw(), result.limitKw(), result.shaveKw());
			if (!wasOver) { // 首超才告警（幂等）
				alarmProducer.publishDemandOverLimit(config, meter, result.demandKw(), result.limitKw(), slotStart);
			}
		}
		else if (!wasOver) {
			// 未超限且槽位尚未定型超限 → 写 NONE（demand_kw 随运行均值定型）；已超限则保留，避免 NONE 覆盖削峰痕迹
			recordService.upsert(newRecord(config, slotStart, slotEnd, avg, limit, false, 0, "NONE"));
		}
	}

	private static EmsDemandRecord newRecord(EmsDemandConfig config, LocalDateTime slotStart, LocalDateTime slotEnd,
			double demandKw, double limitKw, boolean overLimit, double shaveKw, String action) {
		EmsDemandRecord rec = new EmsDemandRecord();
		rec.setTenantId(config.getTenantId());
		rec.setStationId(config.getStationId());
		rec.setWindowStart(slotStart);
		rec.setWindowEnd(slotEnd);
		rec.setDemandKw(BigDecimal.valueOf(round2(demandKw)));
		rec.setLimitKw(BigDecimal.valueOf(round2(limitKw)));
		rec.setOverLimit(overLimit);
		rec.setShavedKw(BigDecimal.valueOf(round2(shaveKw)));
		rec.setAction(action);
		return rec;
	}

	private static double num(BigDecimal v) {
		return v == null ? 0 : v.doubleValue();
	}

	private static double round2(double v) {
		return Math.round(v * 100.0) / 100.0;
	}

}
```

- [ ] **Step 5: 运行测试验证通过**

Run: `mvn -pl energy-ems spring-javaformat:apply && mvn -pl energy-ems test -Dtest=DemandDetectSchedulerTest`
Expected: PASS（7 条）。

- [ ] **Step 6: 提交**

```bash
git add backend/energy-ems/src/main/java/com/energyx/ems/service/DemandAlarmProducer.java \
  backend/energy-ems/src/main/java/com/energyx/ems/scheduler/DemandDetectScheduler.java \
  backend/energy-ems/src/test/java/com/energyx/ems/scheduler/DemandDetectSchedulerTest.java
git commit -m "feat(ems): P1-2 需量检测调度器 + 超限事件发布（每min+分布式锁+首超幂等）"
```

---

### Task 6: DemandSavingsEstimator 纯函数 + 收益 demandSavings 接入

**Files:**
- Create: `backend/energy-ems/src/main/java/com/energyx/ems/util/DemandSavingsEstimator.java`
- Create: `backend/energy-ems/src/main/java/com/energyx/ems/web/dto/DemandSavingsView.java`
- Modify: `backend/energy-ems/src/main/java/com/energyx/ems/service/EmsRevenueService.java`（构造器加 2 参、`summary()` line 107 恒 0 改为接入、新增 `demandSavings(...)` 方法）
- Test: `backend/energy-ems/src/test/java/com/energyx/ems/util/DemandSavingsEstimatorTest.java`
- Modify: `backend/energy-ems/src/test/java/com/energyx/ems/service/EmsRevenueServiceTest.java`（构造器补 2 个 mock + 新增 1 条 demandSavings 接线测试）

**Interfaces:**
- Consumes: `EmsDemandRecordService.listByRange`、`EmsDemandConfigService.getByStation`（Task 1）、`EmsDemandRecord`/`EmsDemandConfig` 实体、`EmsRevenueService.resolveRange` 私有方法（同文件内）。
- Produces（后续任务依赖）：
  - `DemandSavingsEstimator.actualMax(List<EmsDemandRecord>) → double`、`unshavedMax(List<EmsDemandRecord>) → double`、`estimate(List<EmsDemandRecord>, double rate, double periodFactor) → double`
  - `EmsRevenueService.demandSavings(Long stationId, String periodType, LocalDate date) → DemandSavingsView`
  - `DemandSavingsView{stationId, periodType, startDate, endDate, actualMaxKw, unshavedMaxKw, savings}`

- [ ] **Step 1: 写失败测试**

`backend/energy-ems/src/test/java/com/energyx/ems/util/DemandSavingsEstimatorTest.java`：

```java
package com.energyx.ems.util;

import com.energyx.ems.entity.EmsDemandRecord;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** DemandSavingsEstimator：未削峰最大需量加回 shaved_kw；无记录/费率 0 → 0；期数系数生效。 */
class DemandSavingsEstimatorTest {

	private static EmsDemandRecord rec(double demandKw, double shavedKw) {
		EmsDemandRecord r = new EmsDemandRecord();
		r.setDemandKw(BigDecimal.valueOf(demandKw));
		r.setShavedKw(BigDecimal.valueOf(shavedKw));
		return r;
	}

	@Test
	void estimate_addsBackShavedKwForUnshavedMax() {
		// 峰值槽位：实际 500、削峰 200 → 未削峰 700；次高 650 → 未削峰 650
		List<EmsDemandRecord> recs = List.of(rec(500, 200), rec(650, 0), rec(400, 0));
		assertEquals(700.0, DemandSavingsEstimator.unshavedMax(recs));
		assertEquals(650.0, DemandSavingsEstimator.actualMax(recs));
		// (700 − 650) × 40 × 1 = 2000
		assertEquals(2000.0, DemandSavingsEstimator.estimate(recs, 40, 1));
	}

	@Test
	void estimate_noRecordsReturnsZero() {
		assertEquals(0.0, DemandSavingsEstimator.estimate(List.of(), 40, 1));
	}

	@Test
	void estimate_rateZeroReturnsZero() {
		assertEquals(0.0, DemandSavingsEstimator.estimate(List.of(rec(500, 200)), 0, 1));
	}

	@Test
	void estimate_appliesPeriodFactor() {
		List<EmsDemandRecord> recs = List.of(rec(500, 200), rec(650, 0));
		assertEquals(2000.0, DemandSavingsEstimator.estimate(recs, 40, 1));   // 月 ×1
		assertEquals(24000.0, DemandSavingsEstimator.estimate(recs, 40, 12)); // 年 ×12
	}

	@Test
	void estimate_neverNegative() {
		List<EmsDemandRecord> recs = List.of(rec(650, 0), rec(500, 0)); // 无削峰 → 差 0
		assertEquals(0.0, DemandSavingsEstimator.estimate(recs, 40, 1));
	}

}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `mvn -pl energy-ems test -Dtest=DemandSavingsEstimatorTest`
Expected: 编译失败（DemandSavingsEstimator 不存在）。

- [ ] **Step 3: 实现 DemandSavingsEstimator**

`backend/energy-ems/src/main/java/com/energyx/ems/util/DemandSavingsEstimator.java`：

```java
package com.energyx.ems.util;

import com.energyx.ems.entity.EmsDemandRecord;

import java.math.BigDecimal;
import java.util.List;

/**
 * 需量电费节省估算（P1-2，纯函数）。
 *
 * <p>
 * 口径：未削峰最大需量 = max(各槽位 demand_kw + shaved_kw)（削峰放掉的功率加回，估算无电池场景）； 实际最大需量 =
 * max(各槽位 demand_kw)；节省金额 = (未削峰 − 实际) × 费率 × 期数系数。
 * </p>
 */
public final class DemandSavingsEstimator {

	private DemandSavingsEstimator() {
	}

	/** 周期内实际最大需量（kW）。无记录 → 0。 */
	public static double actualMax(List<EmsDemandRecord> records) {
		return records.stream().mapToDouble(r -> num(r.getDemandKw())).max().orElse(0);
	}

	/** 未削峰最大需量（kW）：把削峰时放掉的功率加回。无记录 → 0。 */
	public static double unshavedMax(List<EmsDemandRecord> records) {
		return records.stream().mapToDouble(r -> num(r.getDemandKw()) + num(r.getShavedKw())).max().orElse(0);
	}

	/** 节省金额（元）= (未削峰 − 实际) × 费率 × 期数系数。无记录 / 费率 ≤ 0 → 0，恒非负。 */
	public static double estimate(List<EmsDemandRecord> records, double rate, double periodFactor) {
		if (records.isEmpty() || rate <= 0) {
			return 0;
		}
		return Math.max(0, unshavedMax(records) - actualMax(records)) * rate * periodFactor;
	}

	private static double num(BigDecimal v) {
		return v == null ? 0 : v.doubleValue();
	}

}
```

`backend/energy-ems/src/main/java/com/energyx/ems/web/dto/DemandSavingsView.java`：

```java
package com.energyx.ems.web.dto;

import lombok.Data;

/** 需量节省估算视图（P1-2）。金额 元、功率 kW。 */
@Data
public class DemandSavingsView {

	private Long stationId;

	/** DAY/MONTH/YEAR */
	private String periodType;

	private String startDate;

	private String endDate;

	/** 实际最大需量 kW */
	private double actualMaxKw;

	/** 未削峰最大需量 kW */
	private double unshavedMaxKw;

	/** 节省金额 元 */
	private double savings;

}
```

- [ ] **Step 4: 修改 EmsRevenueService 接入**

`backend/energy-ems/src/main/java/com/energyx/ems/service/EmsRevenueService.java` 五处修改：

**(a)** imports 追加：

```java
import com.energyx.ems.entity.EmsDemandConfig;
import com.energyx.ems.entity.EmsDemandRecord;
import com.energyx.ems.util.DemandSavingsEstimator;
import com.energyx.ems.web.dto.DemandSavingsView;
```

**(b)** 字段 + 构造器（现有 6 参构造器后追加 2 参）：

```java
	private final EmsDemandConfigService configService;

	private final EmsDemandRecordService recordService;

	public EmsRevenueService(EmsPlanMapper planMapper, EmsElectricityPriceMapper priceMapper,
			EmsStationMetaService stationMetaService, PcsDeviceMapper pcsDeviceMapper, TsdbClient tsdbClient,
			TdenginePlanWriter writer, EmsDemandConfigService configService, EmsDemandRecordService recordService) {
		this.planMapper = planMapper;
		this.priceMapper = priceMapper;
		this.stationMetaService = stationMetaService;
		this.pcsDeviceMapper = pcsDeviceMapper;
		this.tsdbClient = tsdbClient;
		this.writer = writer;
		this.configService = configService;
		this.recordService = recordService;
	}
```

**(c)** `summary()` 第 107 行 `s.setDemandSavings(0); // P1-2 前恒 0` 替换为：

```java
		s.setDemandSavings(round2(demandSavings(stationId, periodType, date).getSavings()));
```

**(d)** 新增 public 方法（放在 `meta(...)` 之后）：

```java
	/** 需量节省估算（P1-2）：周期内槽位记录聚合 + 期数系数（月 ×1、年 ×12、日 ×1/30 示意）。无配置费率/无记录 → 0。 */
	public DemandSavingsView demandSavings(Long stationId, String periodType, LocalDate date) {
		Long tenant = requireTenant();
		LocalDate[] range = resolveRange(periodType, date);
		EmsDemandConfig cfg = configService.getByStation(stationId);
		double rate = cfg == null || cfg.getDemandRate() == null ? 0 : cfg.getDemandRate().doubleValue();
		double factor = switch (periodType) {
			case "DAY" -> 1.0 / 30.0; // 示意：按 30 天折算月基本电费
			case "YEAR" -> 12.0;
			default -> 1.0; // MONTH
		};
		List<EmsDemandRecord> recs = recordService.listByRange(tenant, stationId, range[0].atStartOfDay(),
				range[1].atTime(LocalTime.MAX));
		DemandSavingsView view = new DemandSavingsView();
		view.setStationId(stationId);
		view.setPeriodType(periodType);
		view.setStartDate(range[0].toString());
		view.setEndDate(range[1].toString());
		view.setActualMaxKw(round2(DemandSavingsEstimator.actualMax(recs)));
		view.setUnshavedMaxKw(round2(DemandSavingsEstimator.unshavedMax(recs)));
		view.setSavings(round2(DemandSavingsEstimator.estimate(recs, rate, factor)));
		return view;
	}
```

**注意**：`EmsRevenueService` 已 import `java.time.LocalTime`（现用），`requireTenant()`/`resolveRange()` 为已有私有方法，`round2` 为已有私有静态方法，直接复用。

- [ ] **Step 5: 更新 EmsRevenueServiceTest 构造器 + 新增接线测试**

`backend/energy-ems/src/test/java/com/energyx/ems/service/EmsRevenueServiceTest.java` 第 58 行构造器追加 2 个 mock：

```java
		svc = new EmsRevenueService(planMapper, priceMapper, stationMetaService, pcsDeviceMapper, tsdbClient, writer,
				configService, recordService);
```

并在该测试类新增两个字段 + import：

```java
	private EmsDemandConfigService configService;
	private EmsDemandRecordService recordService;
```

（在现有 mock 字段初始化处一并 `configService = mock(EmsDemandConfigService.class); recordService = mock(EmsDemandRecordService.class);`）

新增测试（断言 summary.demandSavings 从配置费率 + 槽位记录算出，不再恒 0）：

```java
	@Test
	void summary_wiresDemandSavingsFromConfigAndRecords() {
		EmsDemandConfig cfg = new EmsDemandConfig();
		cfg.setDemandLimitKw(new BigDecimal("100.00"));
		cfg.setDemandRate(new BigDecimal("40.0000"));
		when(configService.getByStation(10L)).thenReturn(cfg);
		EmsDemandRecord peak = new EmsDemandRecord();
		peak.setDemandKw(new BigDecimal("500.00"));
		peak.setShavedKw(new BigDecimal("200.00"));
		EmsDemandRecord second = new EmsDemandRecord();
		second.setDemandKw(new BigDecimal("650.00"));
		second.setShavedKw(BigDecimal.ZERO);
		when(recordService.listByRange(anyLong(), eq(10L), any(), any()))
				.thenReturn(List.of(peak, second));
		// 未削峰 700 − 实际 650 = 50 × 40 × 月系数 1 = 2000
		RevenueSummary s = svc.summary(10L, "MONTH", LocalDate.of(2026, 8, 11));
		assertEquals(2000.0, s.getDemandSavings(), 0.01);
	}
```

（该测试类需补充 import：`EmsDemandConfig`、`EmsDemandRecord`、`EmsDemandConfigService`、`EmsDemandRecordService`、`java.math.BigDecimal`、`java.util.List`、`com.energyx.ems.entity.*`。**注意**：`summary()` 除 config/record 外还依赖 tsdb/price/stationMeta 等，若新增测试因这些依赖未 stub 而报错，仿同文件既有 summary 测试的 stub 补齐——本测试唯一目的是验证 demandSavings 接线，无配置费率时仍应得 0（既有断言不回归）。）

- [ ] **Step 6: 运行测试验证通过**

Run: `mvn -pl energy-ems spring-javaformat:apply && mvn -pl energy-ems test`
Expected: PASS（DemandSavingsEstimatorTest 5 条 + summary 接线新测试 1 条 + 既有测试全绿，无行为回归——无配置时 demandSavings 仍 0）。

- [ ] **Step 7: 提交**

```bash
git add backend/energy-ems/src/main/java/com/energyx/ems/util/DemandSavingsEstimator.java \
  backend/energy-ems/src/main/java/com/energyx/ems/web/dto/DemandSavingsView.java \
  backend/energy-ems/src/main/java/com/energyx/ems/service/EmsRevenueService.java \
  backend/energy-ems/src/test/java/com/energyx/ems/util/DemandSavingsEstimatorTest.java \
  backend/energy-ems/src/test/java/com/energyx/ems/service/EmsRevenueServiceTest.java
git commit -m "feat(ems): P1-2 需量节省估算接入收益核算（demandSavings 不再恒 0）"
```

---

### Task 7: DemandController + DTO

**Files:**
- Create: `backend/energy-ems/src/main/java/com/energyx/ems/web/DemandController.java`
- Create: `backend/energy-ems/src/main/java/com/energyx/ems/web/dto/DemandConfigReq.java`

**Interfaces:**
- Consumes: `EmsDemandConfigService.getByStation/upsert`、`EmsDemandRecordService.listByRange`（Task 1）、`EmsRevenueService.demandSavings`（Task 6）、`Result.ok`（com.energyx.common.model）、`BusinessException`/`ErrorCode`、`TenantContext`。
- Produces（后续任务依赖的 API 契约，经网关 `/api/ems/demand/**`）：
  - `GET /api/ems/demand/records?stationId&date` → `List<EmsDemandRecord>`
  - `GET /api/ems/demand/config?stationId` → `EmsDemandConfig | null`
  - `PUT /api/ems/demand/config` body `{stationId, demandLimitKw, demandRate}` → `EmsDemandConfig`
  - `GET /api/ems/demand/savings?stationId&periodType&date` → `DemandSavingsView`

- [ ] **Step 1: 写 DemandConfigReq DTO**

`backend/energy-ems/src/main/java/com/energyx/ems/web/dto/DemandConfigReq.java`：

```java
package com.energyx.ems.web.dto;

import lombok.Data;

import java.math.BigDecimal;

/** 需量配置保存请求（P1-2）。 */
@Data
public class DemandConfigReq {

	private Long stationId;

	/** 需量限值 kW（>0 启用检测） */
	private BigDecimal demandLimitKw;

	/** 需量费率 ¥/kW·月 */
	private BigDecimal demandRate;

}
```

- [ ] **Step 2: 写 DemandController**（仿 `EmsRevenueController`）

`backend/energy-ems/src/main/java/com/energyx/ems/web/DemandController.java`：

```java
package com.energyx.ems.web;

import com.energyx.common.exception.BusinessException;
import com.energyx.common.exception.ErrorCode;
import com.energyx.common.model.Result;
import com.energyx.common.tenant.TenantContext;
import com.energyx.ems.entity.EmsDemandConfig;
import com.energyx.ems.entity.EmsDemandRecord;
import com.energyx.ems.service.EmsDemandConfigService;
import com.energyx.ems.service.EmsDemandRecordService;
import com.energyx.ems.service.EmsRevenueService;
import com.energyx.ems.web.dto.DemandConfigReq;
import com.energyx.ems.web.dto.DemandSavingsView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** 需量管理接口（P1-2）。网关 /api/ems/** → energy-ems，控制器映射带 /ems。 */
@RestController
@RequestMapping("/ems/demand")
public class DemandController {

	private final EmsDemandConfigService configService;

	private final EmsDemandRecordService recordService;

	private final EmsRevenueService revenueService;

	public DemandController(EmsDemandConfigService configService, EmsDemandRecordService recordService,
			EmsRevenueService revenueService) {
		this.configService = configService;
		this.recordService = recordService;
		this.revenueService = revenueService;
	}

	/** 某日 96 槽位需量记录（升序）。 */
	@GetMapping("/records")
	public Result<List<EmsDemandRecord>> records(@RequestParam Long stationId, @RequestParam LocalDate date) {
		return Result.ok(recordService.listByRange(requireTenant(), stationId, date.atStartOfDay(),
				date.atTime(23, 59, 59)));
	}

	/** 站点需量配置；未配置返回 null。 */
	@GetMapping("/config")
	public Result<EmsDemandConfig> config(@RequestParam Long stationId) {
		return Result.ok(configService.getByStation(stationId));
	}

	/** 站点需量配置 upsert（限值/费率）。 */
	@PutMapping("/config")
	public Result<EmsDemandConfig> saveConfig(@RequestBody DemandConfigReq req) {
		if (req.getStationId() == null) {
			throw new BusinessException(ErrorCode.PARAM_MISSING, "stationId 必填");
		}
		if (req.getDemandLimitKw() != null && req.getDemandLimitKw().signum() <= 0) {
			throw new BusinessException(ErrorCode.PARAM_INVALID, "需量限值必须大于 0");
		}
		EmsDemandConfig cfg = new EmsDemandConfig();
		cfg.setStationId(req.getStationId());
		cfg.setDemandLimitKw(req.getDemandLimitKw());
		cfg.setDemandRate(req.getDemandRate());
		return Result.ok(configService.upsert(cfg));
	}

	/** 需量节省估算（复用收益服务，避免两套口径）。 */
	@GetMapping("/savings")
	public Result<DemandSavingsView> savings(@RequestParam Long stationId, @RequestParam String periodType,
			@RequestParam LocalDate date) {
		return Result.ok(revenueService.demandSavings(stationId, periodType, date));
	}

	private long requireTenant() {
		Long t = TenantContext.getTenantId();
		if (t == null) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED, "缺少租户上下文");
		}
		return t;
	}

}
```

- [ ] **Step 3: 验证编译**

Run: `mvn -pl energy-ems spring-javaformat:apply && mvn -pl energy-ems compile`
Expected: 编译通过（`ErrorCode.PARAM_MISSING`/`PARAM_INVALID`/`UNAUTHORIZED` 均已在枚举中，P1-1 用过；`Result`/`TenantContext` import 路径与 EmsRevenueController 一致）。

- [ ] **Step 4: 提交**

```bash
git add backend/energy-ems/src/main/java/com/energyx/ems/web/DemandController.java \
  backend/energy-ems/src/main/java/com/energyx/ems/web/dto/DemandConfigReq.java
git commit -m "feat(ems): P1-2 需量管理接口（records/config/savings）"
```

---

### Task 8: 前端类型 + emsApi 需量方法

**Files:**
- Modify: `frontend/src/types/models.ts`（`RevenueSummary` 附近追加 3 个接口）
- Modify: `frontend/src/api/ems.ts`（import 类型 + 追加 4 个方法）

**Interfaces:**
- Consumes: Task 7 的 API 契约；`http`（`frontend/src/api/http.ts`，返回 Promise<data>，业务失败 reject）；`EmsDemandRecord`/`EmsDemandConfig`/`DemandSavingsView` JSON 形状（Long → string，BigDecimal → number，Boolean → boolean）。
- Produces（Task 9 依赖）：
  - `emsApi.demandRecords(stationId: string, date: string): Promise<EmsDemandRecord[]>`
  - `emsApi.demandConfigGet(stationId: string): Promise<EmsDemandConfig | null>`
  - `emsApi.demandConfigPut(body: Partial<EmsDemandConfig>): Promise<EmsDemandConfig>`
  - `emsApi.demandSavings(params: Record<string, unknown>): Promise<DemandSavingsView>`

- [ ] **Step 1: models.ts 追加 3 个类型**（插在 `EmsStationMeta` 接口之后、`// ---------------- 电站 Station ----------------` 之前）

```ts
// ---------------- 需量管理（P1-2） ----------------

export interface EmsDemandConfig {
  demandConfigId?: string
  tenantId?: string
  stationId: string
  /** 需量限值 kW（>0 启用检测） */
  demandLimitKw: number | null
  /** 需量费率 ¥/kW·月 */
  demandRate: number | null
  createTime?: string
  updateTime?: string
}

export interface EmsDemandRecord {
  demandRecordId?: string
  tenantId?: string
  stationId: string
  /** 槽位起点（yyyy-MM-ddTHH:mm:ss） */
  windowStart: string
  /** 槽位终点 */
  windowEnd: string
  /** 槽位实际需量（15min 平均功率 kW） */
  demandKw: number
  /** 限值快照 kW */
  limitKw: number | null
  overLimit: boolean
  /** 削峰放电功率 kW */
  shavedKw: number
  /** NONE/SHED/SHED_FAILED/ALARM_ONLY */
  action: string
  createTime?: string
}

export interface DemandSavingsView {
  stationId: string
  /** DAY/MONTH/YEAR */
  periodType: string
  startDate: string
  endDate: string
  /** 实际最大需量 kW */
  actualMaxKw: number
  /** 未削峰最大需量 kW */
  unshavedMaxKw: number
  /** 节省金额 元 */
  savings: number
}
```

- [ ] **Step 2: ems.ts import + 4 个方法**

第 2 行 import 追加：

```ts
import type { ..., DemandSavingsView, EmsDemandConfig, EmsDemandRecord } from '@/types/models'
```

对象末尾（`revenueMetaPut` 后）追加：

```ts
  /** GET /api/ems/demand/records 某日 96 槽位需量记录 */
  demandRecords(stationId: string, date: string): Promise<EmsDemandRecord[]> {
    return http.get('/api/ems/demand/records', { params: { stationId, date } })
  },

  /** GET /api/ems/demand/config 站点需量配置（未配置返回 null） */
  demandConfigGet(stationId: string): Promise<EmsDemandConfig | null> {
    return http.get('/api/ems/demand/config', { params: { stationId } })
  },

  /** PUT /api/ems/demand/config 保存需量配置（upsert） */
  demandConfigPut(body: Partial<EmsDemandConfig>): Promise<EmsDemandConfig> {
    return http.put('/api/ems/demand/config', body)
  },

  /** GET /api/ems/demand/savings 需量节省估算 */
  demandSavings(params: Record<string, unknown>): Promise<DemandSavingsView> {
    return http.get('/api/ems/demand/savings', { params })
  },
```

- [ ] **Step 3: 验证类型**

Run: `cd frontend && npx vue-tsc --noEmit`
Expected: 无类型错误。

- [ ] **Step 4: 提交**

```bash
git add frontend/src/types/models.ts frontend/src/api/ems.ts
git commit -m "feat(ems-frontend): P1-2 需量类型与 API 方法"
```

---

### Task 9: 前端需量管理页 + 路由 + 侧边栏

**Files:**
- Create: `frontend/src/views/EmsDemand.vue`
- Modify: `frontend/src/router/index.ts`（`ems/revenue` 之后加 `ems/demand`）
- Modify: `frontend/src/layouts/MainLayout.vue`（EMS children 加「需量管理」）

**Interfaces:**
- Consumes: Task 8 的 `emsApi.demand*`、`EmsDemandRecord`/`EmsDemandConfig`/`DemandSavingsView` 类型；`loadStations`（`@/utils/stationDict`）、`useEChart`（`@/composables/useEChart`）、`Station` 类型、CSS 变量（`--ex-*`）、`EmsRevenue.vue` 页面结构。

- [ ] **Step 1: 写 EmsDemand.vue**（仿 `EmsRevenue.vue` 结构：KPI 卡片 + ECharts + el-dialog；曲线为 96 槽位需量 + 限值参考线 + 超限高亮）

`frontend/src/views/EmsDemand.vue`：

```vue
<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { emsApi } from '@/api/ems'
import type { DemandSavingsView, EmsDemandConfig, EmsDemandRecord, Station } from '@/types/models'
import { loadStations } from '@/utils/stationDict'
import { useEChart } from '@/composables/useEChart'

const route = useRoute()

const stations = ref<Station[]>([])
const stationId = ref('')
const periodType = ref<'DAY' | 'MONTH' | 'YEAR'>('DAY')
const date = ref(todayStr())
const loading = ref(false)
const records = ref<EmsDemandRecord[]>([])
const savings = ref<DemandSavingsView | null>(null)
const config = ref<EmsDemandConfig | null>(null)
const chartEl = ref<HTMLElement>()

const PERIODS = [
  { key: 'DAY', label: '日' },
  { key: 'MONTH', label: '月' },
  { key: 'YEAR', label: '年' },
] as const

const ACTION_TEXT: Record<string, string> = {
  NONE: '未超限',
  SHED: '已削峰',
  SHED_FAILED: '削峰失败',
  ALARM_ONLY: '仅告警',
}

function todayStr(): string {
  const d = new Date()
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

async function load(): Promise<void> {
  if (!stationId.value) {
    records.value = []
    savings.value = null
    config.value = null
    return
  }
  loading.value = true
  try {
    const [r, s, c] = await Promise.all([
      emsApi.demandRecords(stationId.value, date.value),
      emsApi.demandSavings({ stationId: stationId.value, periodType: periodType.value, date: date.value }),
      emsApi.demandConfigGet(stationId.value),
    ])
    records.value = r
    savings.value = s
    config.value = c
    refreshChart()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    loading.value = false
  }
}

const { render: renderChart } = useEChart(chartEl)

function fmtKw(v: number | null | undefined): string {
  return v == null ? '—' : `${v.toFixed(1)} kW`
}
function fmtYuan(v: number | null | undefined): string {
  return v == null ? '—' : `¥${v.toFixed(2)}`
}

const violationRows = () => records.value.filter((r) => r.overLimit)

/** 96 槽位需量柱状图 + 限值红色虚线；超限槽位红色高亮。 */
function refreshChart(): void {
  const over = new Set(violationRows().map((r) => r.windowStart))
  renderChart({
    tooltip: { trigger: 'axis' },
    legend: { data: ['需量', '限值'] },
    grid: { left: 60, right: 20, top: 40, bottom: 40 },
    xAxis: { type: 'category', data: records.value.map((r) => r.windowStart.slice(11, 16)), boundaryGap: true },
    yAxis: { type: 'value', name: 'kW' },
    series: [
      {
        name: '需量',
        type: 'bar',
        barMaxWidth: 12,
        data: records.value.map((r) => ({
          value: r.demandKw,
          itemStyle: over.has(r.windowStart) ? { color: '#ef4444' } : { color: '#3b82f6' },
        })),
        markLine: config.value?.demandLimitKw != null
          ? {
              symbol: 'none',
              data: [{ yAxis: config.value.demandLimitKw }],
              lineStyle: { color: '#ef4444', type: 'dashed' },
              label: { formatter: '限值 {c} kW' },
            }
          : undefined,
      },
    ],
  })
}

const configVisible = ref(false)
const savingConfig = ref(false)
const configForm = reactive<{ demandLimitKw: number | null; demandRate: number | null }>({
  demandLimitKw: null,
  demandRate: null,
})

function onChange(): void { void load() }

function openConfig(): void {
  configForm.demandLimitKw = config.value?.demandLimitKw ?? null
  configForm.demandRate = config.value?.demandRate ?? null
  configVisible.value = true
}

async function saveConfig(): Promise<void> {
  if (configForm.demandLimitKw == null || configForm.demandLimitKw <= 0) {
    ElMessage.warning('请输入需量限值（大于 0）')
    return
  }
  savingConfig.value = true
  try {
    await emsApi.demandConfigPut({ stationId: stationId.value, demandLimitKw: configForm.demandLimitKw, demandRate: configForm.demandRate })
    ElMessage.success('已保存')
    configVisible.value = false
    void load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    savingConfig.value = false
  }
}

onMounted(async () => {
  try {
    stations.value = await loadStations()
    const preset = route.query.station
    if (preset) {
      stationId.value = String(preset)
      void load()
    }
  } catch {
    // 电站加载失败由页面空态兜底
  }
})
</script>

<template>
  <div class="ex-page">
    <header class="ex-page-head">
      <div class="head-title">
        <h1 class="ex-title">需量管理</h1>
        <p class="ex-sub">15min 固定槽位需量检测 · 超限实时削峰 · 基本电费节省估算</p>
      </div>
      <el-button v-if="stationId" type="primary" @click="openConfig">需量配置</el-button>
    </header>

    <section class="ex-card filter-card">
      <el-form inline @submit.prevent>
        <el-form-item label="电站">
          <el-select v-model="stationId" placeholder="选择电站" filterable clearable style="width: 260px" @change="onChange">
            <el-option v-for="s in stations" :key="s.stationId" :label="s.stationName" :value="s.stationId" />
          </el-select>
        </el-form-item>
        <el-form-item label="周期">
          <el-radio-group v-model="periodType" @change="onChange">
            <el-radio-button v-for="p in PERIODS" :key="p.key" :value="p.key">{{ p.label }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="日期">
          <el-date-picker v-model="date" :type="periodType === 'DAY' ? 'date' : periodType === 'MONTH' ? 'month' : 'year'"
            value-format="YYYY-MM-DD" :clearable="false" @change="onChange" />
        </el-form-item>
      </el-form>
    </section>

    <section class="ex-card kpi-grid" v-loading="loading">
      <div class="kpi"><span class="kpi-label">实际最大需量</span><span class="kpi-num">{{ fmtKw(savings?.actualMaxKw) }}</span></div>
      <div class="kpi"><span class="kpi-label">未削峰需量</span><span class="kpi-num">{{ fmtKw(savings?.unshavedMaxKw) }}</span></div>
      <div class="kpi"><span class="kpi-label">需量节省</span><span class="kpi-num">{{ fmtYuan(savings?.savings) }}</span></div>
      <div class="kpi"><span class="kpi-label">超限槽位数</span><span class="kpi-num">{{ violationRows().length }}</span></div>
      <div class="kpi"><span class="kpi-label">需量限值</span><span class="kpi-num">{{ fmtKw(config?.demandLimitKw) }}</span></div>
    </section>

    <section class="ex-card chart-card">
      <div ref="chartEl" class="chart" role="img" aria-label="需量曲线"></div>
    </section>

    <section class="ex-card table-card">
      <h3 class="ex-section">超限明细</h3>
      <el-table :data="violationRows()" size="small" empty-text="该日无超限槽位">
        <el-table-column prop="windowStart" label="时间窗" width="150">
          <template #default="{ row }">{{ row.windowStart.slice(5, 16) }} ~ {{ row.windowEnd.slice(11, 16) }}</template>
        </el-table-column>
        <el-table-column prop="demandKw" label="需量 (kW)" align="right" />
        <el-table-column prop="limitKw" label="限值 (kW)" align="right" />
        <el-table-column label="超限量 (kW)" align="right">
          <template #default="{ row }">{{ (row.demandKw - (row.limitKw ?? 0)).toFixed(2) }}</template>
        </el-table-column>
        <el-table-column prop="shavedKw" label="削峰 (kW)" align="right" />
        <el-table-column label="动作" width="110">
          <template #default="{ row }">
            <el-tag :type="row.action === 'SHED' ? 'success' : row.action === 'SHED_FAILED' ? 'danger' : 'warning'" size="small">
              {{ ACTION_TEXT[row.action] ?? row.action }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-dialog v-model="configVisible" title="需量配置" width="440px">
      <el-form label-width="110px" @submit.prevent>
        <el-form-item label="需量限值 (kW)" required>
          <el-input-number v-model="configForm.demandLimitKw" :min="1" :precision="2" :step="100" style="width: 100%" />
        </el-form-item>
        <el-form-item label="需量费率 (元/kW·月)">
          <el-input-number v-model="configForm.demandRate" :min="0" :precision="4" :step="1" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="configVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingConfig" @click="saveConfig">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* 复用 EmsRevenue.vue 的 kpi-grid/kpi/chart/ex-section 视觉（kpi-* 非全局类，需本页定义） */
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(170px, 1fr));
  gap: 14px;
}
.kpi {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.kpi-label {
  font-size: 13px;
  color: var(--ex-ink-2);
}
.kpi-num {
  font-size: 22px;
  font-weight: 600;
  color: var(--ex-ink);
  font-variant-numeric: tabular-nums;
}
.chart {
  height: 360px;
  width: 100%;
}
.ex-section {
  font-size: 15px;
  font-weight: 600;
  color: var(--ex-ink);
  margin: 0 0 12px;
}
</style>
```

- [ ] **Step 2: 路由加 `ems/demand`**

`frontend/src/router/index.ts`，`ems/revenue` 路由项之后插入：

```ts
      {
        path: 'ems/demand',
        name: 'EmsDemand',
        component: () => import('@/views/EmsDemand.vue'),
        meta: { title: '需量管理', icon: 'DataLine' },
      },
```

- [ ] **Step 3: 侧边栏加「需量管理」**

`frontend/src/layouts/MainLayout.vue`，EMS children 的 `{ path: '/ems/revenue', title: '收益核算' }` 之后加：

```ts
      { path: '/ems/demand', title: '需量管理' },
```

- [ ] **Step 4: 验证构建**

Run: `cd frontend && npx vue-tsc --noEmit && npm run build`
Expected: 类型检查 + 构建通过。

- [ ] **Step 5: 提交**

```bash
git add frontend/src/views/EmsDemand.vue frontend/src/router/index.ts frontend/src/layouts/MainLayout.vue
git commit -m "feat(ems-frontend): P1-2 需量管理页面（曲线+超限明细+节省估算+配置弹窗）"
```

---

## 收尾验证（全部任务完成后）

Run:
```
mvn -pl energy-ems spring-javaformat:apply && mvn -pl energy-ems test
cd frontend && npx vue-tsc --noEmit && npm run build
```
Expected: 后端全绿（含新增约 32 条测试）、前端类型与构建通过。运行时冒烟（另需本地 Nacos/MySQL/TDengine/Kafka 起栈）：
1. 重启 energy-ems → Flyway 应用 V6；重启 energy-product → Flyway 应用 V2。
2. `PUT /api/ems/demand/config` 配限值/费率 → 返回配置。
3. 模拟器上报 METER 设备 `importPower`（如峰值 500 kW）→ 每分钟 `DemandDetectScheduler` 检测 → 超限记录 `over_limit=1`、`action=SHED`，Kafka `iot-thing-event` 出现 `demandOverLimit` 事件。
4. `GET /api/ems/demand/records?stationId&date` 返回 96 槽位记录；`GET /api/ems/demand/savings` 返回节省估算；`/ems/revenue/summary` 的 `demandSavings` 不再恒 0。
