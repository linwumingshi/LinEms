# P1-1 收益核算/经济评估 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增收益核算后端接口与前端收益页，按**实际遥测口径**（TSDB `power` 幅值积分 + `runMode` 定方向，回退计划动作）聚合电站日/月/年充放电量与峰谷套利收益，并提供 ROI/回本周期。

**Architecture:** 后端在 energy-ems 内新增 `TsdbClient`（Feign 调 energy-tsdb `property/history`，Nacos 服务名解析）+ 纯函数 `RevenueCalculator`（积分/定方向/电价匹配）+ `EmsRevenueService`（编排：PCS 解析 → 逐日计划/电价 → 计算 → summary/trend/detail）+ `EmsStationMeta`（投资额表）+ 5 个 REST 端点。前端新增 `/ems/revenue` 页（KPI 卡片 + 三档 ECharts + 投资额设置）。

**Tech Stack:** Spring Boot 3.5 / OpenFeign（已配 `@EnableFeignClients`）/ MyBatis-Plus / TDengine TAOS-RS / Vue3 + Element Plus + ECharts / Mockito 纯单测。

## Global Constraints

- **跨服务调用一律 OpenFeign**（Nacos 服务名解析，不写死 URL）；Feign 接口**不用 default 方法**承载业务逻辑（Mockito 会执行真实 default 方法体破坏测试）。
- energy-tsdb 网关 `/api/tsdb/**` StripPrefix=1 → 控制器映射 `/tsdb`；Feign `@FeignClient(name="energy-tsdb", path="/tsdb")`，方法 `@GetMapping("/property/history")`。
- ems 网关 `/api/ems/**` 不 StripPrefix → 新控制器 `@RequestMapping("/ems/revenue")`。
- 租户隔离：条件化拦截器**仅 HTTP 请求线程生效**。租户敏感查询（plan/price/PCS 映射器）必须在**主线**完成；并行子线程只做 TSDB 拉取 + 纯函数计算（无租户依赖）。
- 雪花 Long → JacksonConfig 装箱 Long 序列化为字符串；`primitive long`（如 `RevenueSummary.daysCount` 若用 int/double）保持数字。前端 id 均为 string。
- 电量口径=实际遥测；方向 runMode(1充/2放) 优先、计划动作回退；电价 priceSnapshot 优先、电价表回退（spec §3.2）。
- `spring-javaformat:apply` 后构建必须通过（validate 阶段校验格式）。
- 提交只用显式 pathspec；**永不提交 `frontend/public/`**。
- 测试：纯 Mockito；ServiceImpl 派生服务用 `ReflectionTestUtils.setField(svc, "baseMapper", mock)` + `@BeforeAll TableInfoHelper.initTableInfo(...)`（参照 `EmsPriceServiceTest`）。
- 运行测试：`mvn -pl energy-ems test`；单测定向：`mvn -pl energy-ems test -Dtest='<Class>[,<Class>]' -Dsurefire.failIfNoSpecifiedTests=false`。

---
## 文件结构总览

**后端 energy-ems 新增**：`client/TsdbFeignClient`、`client/TsdbHistoryViewDto`、`client/TsdbHistoryRecordDto`、`service/TsdbClient`、`util/RevenueCalculator`、`util/RevenueSlot`、`util/RevenueDailyResult`、`entity/EmsStationMeta`、`mapper/EmsStationMetaMapper`、`service/EmsStationMetaService`、`web/dto/RevenueSummary/RevenueTrendPoint/RevenueDetailRow/RevenueMetaReq`、`service/EmsRevenueService`、`web/EmsRevenueController`、`resources/db/migration/V5__revenue_meta.sql`。

**后端 energy-ems 测试新增**：`service/TsdbClientTest`、`util/RevenueCalculatorTest`、`service/EmsStationMetaServiceTest`、`service/EmsRevenueServiceTest`。

**前端新增/修改**：`views/EmsRevenue.vue`、`router/index.ts`、`api/ems.ts`、`types/models.ts`。

---

### Task 1: TSDB 跨服务 Feign 基建 + 包装层

**Files:**
- Create: `backend/energy-ems/src/main/java/com/energyx/ems/client/TsdbFeignClient.java`
- Create: `backend/energy-ems/src/main/java/com/energyx/ems/client/TsdbHistoryViewDto.java`
- Create: `backend/energy-ems/src/main/java/com/energyx/ems/client/TsdbHistoryRecordDto.java`
- Create: `backend/energy-ems/src/main/java/com/energyx/ems/service/TsdbClient.java`
- Test: `backend/energy-ems/src/test/java/com/energyx/ems/service/TsdbClientTest.java`

**Interfaces:**
- Consumes: `com.energyx.common.model.Result`、`energy-tsdb` 服务（Nacos 注册名）、`EnergyEmsApplication` 已配 `@EnableFeignClients(basePackages="com.energyx.ems.client")`。
- Produces: `service/TsdbClient.history(long deviceId, String productKey, LocalDate date) → List<TsdbClient.TelemetryRow>`，其中 `TelemetryRow(long ts, Double power, Integer runMode)`（ts=epoch 毫秒，power=kW 可空，runMode=1充/2放可空）。Task 2/4 消费此签名。

- [ ] **Step 1: 写失败测试 `TsdbClientTest`**

```java
package com.energyx.ems.service;

import com.energyx.common.model.Result;
import com.energyx.ems.client.TsdbFeignClient;
import com.energyx.ems.client.TsdbHistoryRecordDto;
import com.energyx.ems.client.TsdbHistoryViewDto;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** TsdbClient.history 解析/分页/失败降级：任一步失败返回空列表，不抛异常。 */
class TsdbClientTest {

	private static final LocalDate DAY = LocalDate.of(2026, 8, 1);

	private static TsdbHistoryRecordDto row(long ts, Double power, Integer runMode) {
		TsdbHistoryRecordDto r = new TsdbHistoryRecordDto();
		r.setTs(ts);
		Map<String, Object> values = new LinkedHashMap<>();
		if (power != null)
			values.put("power", power);
		if (runMode != null)
			values.put("runMode", runMode);
		r.setValues(values);
		return r;
	}

	private static TsdbHistoryViewDto view(long total, List<TsdbHistoryRecordDto> records) {
		TsdbHistoryViewDto v = new TsdbHistoryViewDto();
		v.setTotal(total);
		v.setRecords(records);
		return v;
	}

	private static TsdbClient newClient(TsdbFeignClient feign) {
		return new TsdbClient(feign);
	}

	@Test
	void history_parsesPowerAndRunMode() {
		TsdbFeignClient feign = mock(TsdbFeignClient.class);
		long start = DAY.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
		when(feign.history(anyString(), anyString(), anyString(), anyLong(), anyLong(), anyString(), anyInt(), anyInt()))
			.thenReturn(Result.ok(view(1, List.of(row(start, 60.0, 1)))));
		TsdbClient client = newClient(feign);

		List<TsdbClient.TelemetryRow> rows = client.history(9L, "snd_ess_pcs", DAY);

		assertEquals(1, rows.size());
		assertEquals(60.0, rows.get(0).power());
		assertEquals(1, rows.get(0).runMode());
		assertEquals(start, rows.get(0).ts());
	}

	@Test
	void history_missingValuesMapsNull() {
		TsdbFeignClient feign = mock(TsdbFeignClient.class);
		long start = DAY.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
		when(feign.history(anyString(), anyString(), anyString(), anyLong(), anyLong(), anyString(), anyInt(), anyInt()))
			.thenReturn(Result.ok(view(1, List.of(row(start, null, null)))));
		TsdbClient client = newClient(feign);

		List<TsdbClient.TelemetryRow> rows = client.history(9L, "snd_ess_pcs", DAY);

		assertEquals(1, rows.size());
		assertNull(rows.get(0).power());
		assertNull(rows.get(0).runMode());
	}

	@Test
	void history_businessErrorReturnsEmpty() {
		TsdbFeignClient feign = mock(TsdbFeignClient.class);
		when(feign.history(anyString(), anyString(), anyString(), anyLong(), anyLong(), anyString(), anyInt(), anyInt()))
			.thenReturn(Result.fail(com.energyx.common.exception.ErrorCode.PARAM_INVALID, "bad"));
		TsdbClient client = newClient(feign);

		assertTrue(client.history(9L, "snd_ess_pcs", DAY).isEmpty());
	}

	@Test
	void history_feignThrowsReturnsEmpty() {
		TsdbFeignClient feign = mock(TsdbFeignClient.class);
		when(feign.history(anyString(), anyString(), anyString(), anyLong(), anyLong(), anyString(), anyInt(), anyInt()))
			.thenThrow(new RuntimeException("connection refused"));
		TsdbClient client = newClient(feign);

		assertTrue(client.history(9L, "snd_ess_pcs", DAY).isEmpty());
	}

	@Test
	void history_paginatesUntilTotalCovered() {
		TsdbFeignClient feign = mock(TsdbFeignClient.class);
		long start = DAY.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
		// 两页：page1 满 1000 行，page2 余 2 行，total=1002
		java.util.ArrayList<TsdbHistoryRecordDto> page1 = new java.util.ArrayList<>();
		for (int i = 0; i < 1000; i++)
			page1.add(row(start + i * 60_000L, 50.0, 2));
		when(feign.history(anyString(), anyString(), anyString(), anyLong(), anyLong(), eq("asc"), eq(1), eq(1000)))
			.thenReturn(Result.ok(view(1002, page1)));
		when(feign.history(anyString(), anyString(), anyString(), anyLong(), anyLong(), eq("asc"), eq(2), eq(1000)))
			.thenReturn(Result.ok(view(1002, List.of(row(start + 1000 * 60_000L, 40.0, 1), row(start + 1001 * 60_000L, 40.0, 1)))));
		TsdbClient client = newClient(feign);

		List<TsdbClient.TelemetryRow> rows = client.history(9L, "snd_ess_pcs", DAY);

		assertEquals(1002, rows.size());
		verify(feign).history(anyString(), anyString(), anyString(), anyLong(), anyLong(), eq("asc"), eq(2), eq(1000));
	}

	@Test
	void history_page2FailureReturnsEmpty() {
		// 第 1 页满 1000 行、total=2000，第 2 页业务失败 → 整体返回空列表（不返回部分数据）
		TsdbFeignClient feign = mock(TsdbFeignClient.class);
		long start = DAY.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
		java.util.ArrayList<TsdbHistoryRecordDto> page1 = new java.util.ArrayList<>();
		for (int i = 0; i < 1000; i++)
			page1.add(row(start + i * 60_000L, 50.0, 2));
		when(feign.history(anyString(), anyString(), anyString(), anyLong(), anyLong(), eq("asc"), eq(1), eq(1000)))
			.thenReturn(Result.ok(view(2000, page1)));
		when(feign.history(anyString(), anyString(), anyString(), anyLong(), anyLong(), eq("asc"), eq(2), eq(1000)))
			.thenReturn(Result.fail(com.energyx.common.exception.ErrorCode.PARAM_INVALID, "boom"));
		TsdbClient client = newClient(feign);

		assertTrue(client.history(9L, "snd_ess_pcs", DAY).isEmpty());
	}

	@Test
	void history_nullResultReturnsEmpty() {
		TsdbFeignClient feign = mock(TsdbFeignClient.class);
		when(feign.history(anyString(), anyString(), anyString(), anyLong(), anyLong(), anyString(), anyInt(), anyInt()))
			.thenReturn(null);
		TsdbClient client = newClient(feign);

		assertTrue(client.history(9L, "snd_ess_pcs", DAY).isEmpty());
	}

	@Test
	void history_nullRecordsReturnsEmpty() {
		TsdbFeignClient feign = mock(TsdbFeignClient.class);
		TsdbHistoryViewDto v = new TsdbHistoryViewDto();
		v.setTotal(0);
		v.setRecords(null);
		when(feign.history(anyString(), anyString(), anyString(), anyLong(), anyLong(), anyString(), anyInt(), anyInt()))
			.thenReturn(Result.ok(v));
		TsdbClient client = newClient(feign);

		assertTrue(client.history(9L, "snd_ess_pcs", DAY).isEmpty());
	}

	@Test
	void history_emptyRecordsReturnsEmpty() {
		TsdbFeignClient feign = mock(TsdbFeignClient.class);
		when(feign.history(anyString(), anyString(), anyString(), anyLong(), anyLong(), anyString(), anyInt(), anyInt()))
			.thenReturn(Result.ok(view(0, List.of())));
		TsdbClient client = newClient(feign);

		assertTrue(client.history(9L, "snd_ess_pcs", DAY).isEmpty());
	}
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl energy-ems test -Dtest='TsdbClientTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL（编译错误——类不存在）

- [ ] **Step 3: 写实现**

`client/TsdbFeignClient.java`：
```java
package com.energyx.ems.client;

import com.energyx.common.model.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * energy-tsdb 时序读客户端（P1-1 收益核算）。按 Nacos 服务名解析，不写死 URL（跨服务 Feign 契约）。
 * 网关 /api/tsdb/** StripPrefix=1 → tsdb 控制器映射 /tsdb，Feign path 用 /tsdb。
 */
@FeignClient(name = "energy-tsdb", path = "/tsdb")
public interface TsdbFeignClient {

	/** 属性历史查询（identifiers 逗号分隔如 "power,runMode"，size ≤1000；参数全部显式传入） */
	@GetMapping("/property/history")
	Result<TsdbHistoryViewDto> history(@RequestParam("deviceId") String deviceId,
			@RequestParam("productKey") String productKey, @RequestParam("identifiers") String identifiers,
			@RequestParam("startTime") long startTime, @RequestParam("endTime") long endTime,
			@RequestParam("order") String order, @RequestParam("page") int page, @RequestParam("size") int size);

}
```

`client/TsdbHistoryViewDto.java`：
```java
package com.energyx.ems.client;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 属性历史分页视图投影（本地 DTO，不依赖 energy-tsdb 模块）。total 用 primitive long 保持数字序列化。 */
@Data
public class TsdbHistoryViewDto {

	private String deviceId;

	private String productKey;

	private long total;

	private List<TsdbHistoryRecordDto> records = new ArrayList<>();

}
```

`client/TsdbHistoryRecordDto.java`：
```java
package com.energyx.ems.client;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/** 属性历史单行投影。ts 用 primitive long 保持数字序列化；values 缺键表示该时刻未上报该属性。 */
@Data
public class TsdbHistoryRecordDto {

	private long ts;

	private Map<String, Object> values = new LinkedHashMap<>();

}
```

`service/TsdbClient.java`：
```java
package com.energyx.ems.service;

import com.energyx.common.model.Result;
import com.energyx.ems.client.TsdbFeignClient;
import com.energyx.ems.client.TsdbHistoryRecordDto;
import com.energyx.ems.client.TsdbHistoryViewDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 设备遥测时序读取包装（P1-1 收益核算）。底层走 Feign（Nacos 服务名 energy-tsdb 解析，无硬编码 URL）。
 * 一次查询取 power+runMode 两个属性；分页循环拉满当天；任一步失败返回空列表（单设备查询失败不影响电站整体核算）。
 */
@Slf4j
@Component
public class TsdbClient {

	/** 单页上限，与 tsdb 服务端一致 */
	private static final int PAGE_SIZE = 1000;

	private final TsdbFeignClient feignClient;

	public TsdbClient(TsdbFeignClient feignClient) {
		this.feignClient = feignClient;
	}

	/** 遥测采样行：ts(epoch 毫秒)、power(kW，可空)、runMode(1充/2放，可空) */
	public record TelemetryRow(long ts, Double power, Integer runMode) {
	}

	/** 拉取设备某日 power+runMode 遥测（按 ts 升序）。分页循环直到拉满 total；失败返回空列表并告警。 */
	public List<TelemetryRow> history(long deviceId, String productKey, LocalDate date) {
		try {
			long start = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
			long end = date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
			List<TelemetryRow> out = new ArrayList<>();
			int page = 1;
			long total = Long.MAX_VALUE;
			while (out.size() < total) {
				Result<TsdbHistoryViewDto> result = feignClient.history(String.valueOf(deviceId), productKey,
						"power,runMode", start, end, "asc", page, PAGE_SIZE);
				if (result == null || !result.isSuccess()) {
					log.warn("TSDB 查询失败 deviceId={} date={} code={} msg={}", deviceId, date,
							result == null ? -1 : result.getCode(), result == null ? "null" : result.getMessage());
					return List.of(); // 任一步失败返回空列表（含中分页失败，空列表可检测、部分数据会静默少算收益）
				}
				TsdbHistoryViewDto view = result.getData();
				if (view == null || view.getRecords() == null || view.getRecords().isEmpty()) {
					return List.of();
				}
				total = view.getTotal();
				for (TsdbHistoryRecordDto rec : view.getRecords()) {
					Double power = number(rec.getValues(), "power");
					Double rm = number(rec.getValues(), "runMode");
					out.add(new TelemetryRow(rec.getTs(), power, rm == null ? null : rm.intValue()));
				}
				if (view.getRecords().size() < PAGE_SIZE) {
					break;
				}
				page++;
			}
			out.sort((a, b) -> Long.compare(a.ts(), b.ts()));
			return out;
		}
		catch (Exception e) {
			log.warn("拉取遥测失败 deviceId={} date={}: {}", deviceId, date, e.getMessage());
			return List.of();
		}
	}

	private static Double number(Map<String, Object> values, String key) {
		Object v = values.get(key);
		return v instanceof Number n ? n.doubleValue() : null;
	}

}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl energy-ems test -Dtest='TsdbClientTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（9 条）

- [ ] **Step 5: 格式 + 提交**

Run: `mvn -pl energy-ems spring-javaformat:apply`
Run: `mvn -pl energy-ems validate`
Run:
```bash
git add backend/energy-ems/src/main/java/com/energyx/ems/client/TsdbFeignClient.java backend/energy-ems/src/main/java/com/energyx/ems/client/TsdbHistoryViewDto.java backend/energy-ems/src/main/java/com/energyx/ems/client/TsdbHistoryRecordDto.java backend/energy-ems/src/main/java/com/energyx/ems/service/TsdbClient.java backend/energy-ems/src/test/java/com/energyx/ems/service/TsdbClientTest.java
git commit -m "feat(ems): P1-1 收益核算 TSDB 跨服务 Feign 基建（energy-tsdb 遥测拉取）"
```

---

### Task 2: RevenueCalculator 聚合核心（纯函数）

**Files:**
- Create: `backend/energy-ems/src/main/java/com/energyx/ems/util/RevenueSlot.java`
- Create: `backend/energy-ems/src/main/java/com/energyx/ems/util/RevenueDailyResult.java`
- Create: `backend/energy-ems/src/main/java/com/energyx/ems/util/RevenueCalculator.java`
- Test: `backend/energy-ems/src/test/java/com/energyx/ems/util/RevenueCalculatorTest.java`

**Interfaces:**
- Consumes: `service/TsdbClient.TelemetryRow`（Task 1）。
- Produces: `RevenueCalculator.aggregateDay(LocalDate date, List<TelemetryRow> rows, Function<LocalTime,String> planAction, Function<LocalTime,Double> price) → RevenueDailyResult`；`RevenueDailyResult(LocalDate date, double chargeEnergy, double dischargeEnergy, double revenue, List<RevenueSlot> slots)`；`RevenueSlot(LocalTime time, String action, double energyKwh, double price, double revenue, String source)`。Task 4 消费。

- [ ] **Step 1: 写失败测试 `RevenueCalculatorTest`**

```java
package com.energyx.ems.util;

import com.energyx.ems.service.TsdbClient.TelemetryRow;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** RevenueCalculator.aggregateDay 聚合语义：方向/积分/钳制/电价/收益符号。 */
class RevenueCalculatorTest {

	private static final LocalDate DAY = LocalDate.of(2026, 8, 1);

	private static final Function<LocalTime, String> NO_PLAN = null;

	private static final Function<LocalTime, Double> NO_PRICE = null;

	/** 构造一行遥测：从 00:00 起第 n 分钟。 */
	private static TelemetryRow row(int minute, double power, Integer runMode) {
		long ts = DAY.atTime(LocalTime.of(0, 0)).plusMinutes(minute).atZone(java.time.ZoneId.systemDefault())
			.toInstant().toEpochMilli();
		return new TelemetryRow(ts, power, runMode);
	}

	@Test
	void runModeWinsOverPlanAction() {
		// runMode=1（充）但计划该刻为 DISCHARGE → 按充电计
		Function<LocalTime, String> plan = t -> "DISCHARGE";
		RevenueDailyResult r = RevenueCalculator.aggregateDay(DAY,
				List.of(row(0, 60, 1), row(10, 60, 1)), plan, NO_PRICE);

		assertEquals(10.0, r.chargeEnergy(), 1e-9); // 60kW × 10/60h × 2 段
		assertEquals(0.0, r.dischargeEnergy(), 1e-9);
	}

	@Test
	void fallsBackToPlanActionWhenNoRunMode() {
		Function<LocalTime, String> plan = t -> "DISCHARGE";
		RevenueDailyResult r = RevenueCalculator.aggregateDay(DAY,
				List.of(row(0, 60, null), row(10, 60, null)), plan, NO_PRICE);

		assertEquals(10.0, r.dischargeEnergy(), 1e-9);
	}

	@Test
	void unknownDirectionSkipped() {
		// 无 runMode 且无计划 → 槽位不参与
		RevenueDailyResult r = RevenueCalculator.aggregateDay(DAY,
				List.of(row(0, 60, null), row(10, 60, null)), NO_PLAN, NO_PRICE);

		assertEquals(0.0, r.chargeEnergy(), 1e-9);
		assertEquals(0.0, r.dischargeEnergy(), 1e-9);
		assertTrue(r.slots().isEmpty());
	}

	@Test
	void integratesEnergyOverInterval() {
		// 10 分钟间隔：60kW → 10 kWh
		RevenueDailyResult r = RevenueCalculator.aggregateDay(DAY,
				List.of(row(0, 60, 2), row(10, 60, 2)), NO_PLAN, NO_PRICE);

		assertEquals(10.0, r.dischargeEnergy(), 1e-9);
	}

	@Test
	void clampsLongGap() {
		// 5 小时间隔 → dt 钳制 1h：能量 = 60×1 = 60，而非 300
		RevenueDailyResult r = RevenueCalculator.aggregateDay(DAY,
				List.of(row(0, 60, 2), row(300, 60, 2)), NO_PLAN, NO_PRICE);

		assertEquals(60.0, r.dischargeEnergy(), 1e-9);
	}

	@Test
	void zeroPowerSkipped() {
		RevenueDailyResult r = RevenueCalculator.aggregateDay(DAY,
				List.of(row(0, 0, 1), row(10, 0, 1)), NO_PLAN, NO_PRICE);

		assertEquals(0.0, r.chargeEnergy(), 1e-9);
	}

	@Test
	void noPriceCountsEnergyButZeroRevenue() {
		RevenueDailyResult r = RevenueCalculator.aggregateDay(DAY,
				List.of(row(0, 60, 2), row(10, 60, 2)), NO_PLAN, NO_PRICE);

		assertEquals(10.0, r.dischargeEnergy(), 1e-9);
		assertEquals(0.0, r.revenue(), 1e-9);
	}

	@Test
	void revenueSignChargeSubtractsDischargeAdds() {
		// 00:00/00:05 充 60kW（各 5min=5kWh）、00:10/00:15 放 60kW（各 5min）@0.3 充、@1.0 放 → 收益 = 10×1.0 − 10×0.3 = 7
		// 第 5 行 00:20 为末采样点（无后继间隔，不计），保证充/放两簇各 2 槽全部贡献
		Function<LocalTime, String> plan = t -> t.getMinute() >= 10 ? "DISCHARGE" : "CHARGE";
		Function<LocalTime, Double> price = t -> t.getMinute() >= 10 ? 1.0 : 0.3;
		RevenueDailyResult r = RevenueCalculator.aggregateDay(DAY,
				List.of(row(0, 60, null), row(5, 60, null), row(10, 60, null), row(15, 60, null), row(20, 60, null)),
				plan, price);

		assertEquals(10.0, r.chargeEnergy(), 1e-9);
		assertEquals(10.0, r.dischargeEnergy(), 1e-9);
		assertEquals(7.0, r.revenue(), 1e-9);
	}

	@Test
	void sourceMarkedRunModeOrPlan() {
		Function<LocalTime, String> plan = t -> "DISCHARGE";
		// 第 3 行 00:20 为末采样点（不计），保证第 2 行 00:10 无 runMode 时回退计划、仍产生 PLAN 槽
		RevenueDailyResult r = RevenueCalculator.aggregateDay(DAY,
				List.of(row(0, 60, 1), row(10, 60, null), row(20, 60, null)), plan, NO_PRICE);

		assertEquals("RUN_MODE", r.slots().get(0).source());
		assertEquals("PLAN", r.slots().get(1).source());
	}

	@Test
	void standbyRunModeFallsBackToPlanAndSourcePlan() {
		// runMode==0（待机）非 1/2 → 方向回退计划 DISCHARGE；source 必须标 PLAN（非 RUN_MODE）
		Function<LocalTime, String> plan = t -> "DISCHARGE";
		RevenueDailyResult r = RevenueCalculator.aggregateDay(DAY,
				List.of(row(0, 60, 0), row(10, 60, 0)), plan, NO_PRICE);

		assertEquals(10.0, r.dischargeEnergy(), 1e-9);
		assertEquals("PLAN", r.slots().get(0).source());
	}
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl energy-ems test -Dtest='RevenueCalculatorTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL（编译错误——类不存在）

- [ ] **Step 3: 写实现**

`util/RevenueSlot.java`：
```java
package com.energyx.ems.util;

import java.time.LocalTime;

/** 收益核算单槽结果（单日明细行，P1-1）。time 为遥测采样时刻、action CHARGE/DISCHARGE、source 为方向来源。 */
public record RevenueSlot(LocalTime time, String action, double energyKwh, double price, double revenue,
		String source) {
}
```

`util/RevenueDailyResult.java`：
```java
package com.energyx.ems.util;

import java.time.LocalDate;
import java.util.List;

/** 收益核算单日聚合：逐槽明细 + 当日累计（P1-1）。 */
public record RevenueDailyResult(LocalDate date, double chargeEnergy, double dischargeEnergy, double revenue,
		List<RevenueSlot> slots) {
}
```

`util/RevenueCalculator.java`：
```java
package com.energyx.ems.util;

import com.energyx.ems.service.TsdbClient.TelemetryRow;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 收益聚合纯函数（P1-1）：按实际遥测积分电量、runMode 定方向（回退计划动作）、电价匹配，累计套利收益。
 * 不碰 DB/Feign，输入输出全在签名，便于单测。
 */
public final class RevenueCalculator {

	/** 相邻采样点最大间隔（小时）：缺报长间隔不按满功率计，防数据空洞虚增电量 */
	public static final double MAX_SLOT_HOURS = 1.0;

	private RevenueCalculator() {
	}

	/**
	 * 单日聚合：对每行非零遥测，energy = |power| × dt（dt 钳制），方向 runMode 优先、计划动作回退，
	 * 电价匹配时段档（无电价计电量、收益记 0）。收益 = Σ(放电电量×价) − Σ(充电电量×价)。
	 * @param date 核算日期
	 * @param rows 遥测采样行（按 ts 升序；调用方保证）
	 * @param planAction 时刻→计划动作（CHARGE/DISCHARGE），无计划时刻返回 null；可为 null（全部靠 runMode）
	 * @param price 时刻→电价(元/kWh)，无电价返回 null；可为 null（全部收益记 0）
	 */
	public static RevenueDailyResult aggregateDay(LocalDate date, List<TelemetryRow> rows,
			Function<LocalTime, String> planAction, Function<LocalTime, Double> price) {
		double chargeEnergy = 0;
		double dischargeEnergy = 0;
		double revenue = 0;
		List<RevenueSlot> slots = new ArrayList<>();
		for (int i = 0; i < rows.size(); i++) {
			TelemetryRow row = rows.get(i);
			if (row.power() == null || row.power() == 0) {
				continue;
			}
			double dt = slotHours(rows, i);
			if (dt <= 0) {
				continue; // 末采样点无后继间隔，不计
			}
			LocalTime time = localTimeOf(row.ts());
			String action = resolveAction(row, planAction, time);
			if (action == null) {
				continue; // 方向未知（无 runMode 且无计划动作），该槽不参与
			}
			double energy = Math.abs(row.power()) * dt;
			double unitPrice = 0;
			if (price != null) {
				Double p = price.apply(time);
				unitPrice = p == null ? 0 : p;
			}
			double slotRevenue = "CHARGE".equals(action) ? -energy * unitPrice : energy * unitPrice;
			chargeEnergy += "CHARGE".equals(action) ? energy : 0;
			dischargeEnergy += "DISCHARGE".equals(action) ? energy : 0;
			revenue += slotRevenue;
			// 方向来源：方向确实由 runMode 决定（1充/2放）才标 RUN_MODE；runMode==0 待机回退计划 → PLAN
			String source = row.runMode() != null && (row.runMode() == 1 || row.runMode() == 2) ? "RUN_MODE" : "PLAN";
			slots.add(new RevenueSlot(time, action, energy, unitPrice, slotRevenue, source));
		}
		return new RevenueDailyResult(date, chargeEnergy, dischargeEnergy, revenue, slots);
	}

	/** 左采样点覆盖间隔 = clamp((ts[i+1]-ts[i])/3600e3, 0, MAX_SLOT_HOURS)；末点返回 0。 */
	private static double slotHours(List<TelemetryRow> rows, int i) {
		if (i + 1 >= rows.size()) {
			return 0;
		}
		double hours = (rows.get(i + 1).ts() - rows.get(i).ts()) / 3_600_000.0;
		if (hours < 0) {
			hours = 0;
		}
		return Math.min(hours, MAX_SLOT_HOURS);
	}

	/** runMode(1充/2放) 优先；缺失回退计划动作；两者皆无返回 null。 */
	private static String resolveAction(TelemetryRow row, Function<LocalTime, String> planAction, LocalTime time) {
		if (row.runMode() != null) {
			if (row.runMode() == 1) {
				return "CHARGE";
			}
			if (row.runMode() == 2) {
				return "DISCHARGE";
			}
		}
		if (planAction != null) {
			String action = planAction.apply(time);
			if (action != null && ("CHARGE".equals(action) || "DISCHARGE".equals(action))) {
				return action;
			}
		}
		return null;
	}

	private static LocalTime localTimeOf(long tsMs) {
		return LocalTime.ofInstant(Instant.ofEpochMilli(tsMs), ZoneId.systemDefault());
	}

}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl energy-ems test -Dtest='RevenueCalculatorTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（10 条）

- [ ] **Step 5: 格式 + 提交**

Run: `mvn -pl energy-ems spring-javaformat:apply`
Run: `mvn -pl energy-ems validate`
Run:
```bash
git add backend/energy-ems/src/main/java/com/energyx/ems/util/RevenueSlot.java backend/energy-ems/src/main/java/com/energyx/ems/util/RevenueDailyResult.java backend/energy-ems/src/main/java/com/energyx/ems/util/RevenueCalculator.java backend/energy-ems/src/test/java/com/energyx/ems/util/RevenueCalculatorTest.java
git commit -m "feat(ems): P1-1 收益核算聚合核心（实际遥测积分 + runMode 定方向 + 电价匹配）"
```

---

### Task 3: 电站投资元数据（表 + 实体 + upsert）

**Files:**
- Create: `backend/energy-ems/src/main/resources/db/migration/V5__revenue_meta.sql`
- Create: `backend/energy-ems/src/main/java/com/energyx/ems/entity/EmsStationMeta.java`
- Create: `backend/energy-ems/src/main/java/com/energyx/ems/mapper/EmsStationMetaMapper.java`
- Create: `backend/energy-ems/src/main/java/com/energyx/ems/service/EmsStationMetaService.java`
- Test: `backend/energy-ems/src/test/java/com/energyx/ems/service/EmsStationMetaServiceTest.java`

**Interfaces:**
- Consumes: 无（自包含）。
- Produces: `service/EmsStationMetaService.getByStation(Long stationId) → EmsStationMeta|null`、`service/EmsStationMetaService.upsert(EmsStationMeta meta) → EmsStationMeta`。Task 4 消费（ROI 数据源）。

- [ ] **Step 1: 写失败测试 `EmsStationMetaServiceTest`**

```java
package com.energyx.ems.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.energyx.common.tenant.TenantContext;
import com.energyx.common.tenant.TenantInfo;
import com.energyx.ems.entity.EmsStationMeta;
import com.energyx.ems.mapper.EmsStationMetaMapper;
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
import static org.mockito.Mockito.*;

/** EmsStationMetaService.upsert 幂等：同站已存在原位更新，否则插入。 */
class EmsStationMetaServiceTest {

	@BeforeAll
	static void registerTableInfo() {
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
				EmsStationMeta.class);
	}

	@BeforeEach
	void setTenant() {
		TenantContext.set(new TenantInfo(7L, 100L));
	}

	@AfterEach
	void clearTenant() {
		TenantContext.clear();
	}

	private static EmsStationMetaService newService(EmsStationMetaMapper mapper) {
		EmsStationMetaService svc = new EmsStationMetaService();
		ReflectionTestUtils.setField(svc, "baseMapper", mapper);
		return svc;
	}

	@Test
	void upsert_firstInsertSetsTenantAndNoPk() {
		EmsStationMetaMapper mapper = mock(EmsStationMetaMapper.class);
		when(mapper.selectOne(any())).thenReturn(null);
		EmsStationMetaService svc = newService(mapper);

		EmsStationMeta meta = new EmsStationMeta();
		meta.setStationId(10L);
		meta.setInvestmentAmount(new BigDecimal("1000000"));
		svc.upsert(meta);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<EmsStationMeta> captor = ArgumentCaptor.forClass(EmsStationMeta.class);
		verify(mapper).insert(captor.capture());
		verify(mapper, never()).updateById(any(EmsStationMeta.class));
		assertNull(captor.getValue().getStationMetaId());
		assertEquals(7L, captor.getValue().getTenantId());
	}

	@Test
	void upsert_resubmitUpdatesInPlace() {
		EmsStationMeta existing = new EmsStationMeta();
		existing.setStationMetaId(1L);
		existing.setStationId(10L);
		EmsStationMetaMapper mapper = mock(EmsStationMetaMapper.class);
		when(mapper.selectOne(any())).thenReturn(existing);
		EmsStationMetaService svc = newService(mapper);

		EmsStationMeta meta = new EmsStationMeta();
		meta.setStationId(10L);
		meta.setInvestmentAmount(new BigDecimal("2000000"));
		svc.upsert(meta);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<EmsStationMeta> upd = ArgumentCaptor.forClass(EmsStationMeta.class);
		verify(mapper).updateById(upd.capture());
		assertEquals(1L, upd.getValue().getStationMetaId()); // 原位更新，保留主键
		assertEquals(0, new BigDecimal("2000000").compareTo(upd.getValue().getInvestmentAmount()));
	}
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl energy-ems test -Dtest='EmsStationMetaServiceTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL（编译错误——类不存在）

- [ ] **Step 3: 写实现**

`V5__revenue_meta.sql`：
```sql
-- =====================================================================
-- EnergyX 储能管理平台 · ems 域（energy-ems 服务）
-- V5__revenue_meta.sql —— 收益核算电站投资元数据（P1-1）
-- 版本：v1.4    日期：2026-08-11
-- 说明：ROI/回本周期数据源。投资额/投运日期在收益页录入，station_id 唯一。
-- =====================================================================

CREATE TABLE `ems_station_meta` (
  `station_meta_id`   BIGINT        NOT NULL AUTO_INCREMENT,
  `tenant_id`         BIGINT        NOT NULL,
  `station_id`        BIGINT        NOT NULL,
  `investment_amount` DECIMAL(12,2)          DEFAULT NULL COMMENT '投资额 元',
  `install_date`      DATE                   DEFAULT NULL COMMENT '投运日期',
  `create_time`       DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`       DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`station_meta_id`),
  UNIQUE KEY `uk_station_meta_station` (`station_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='收益核算电站投资元数据';
```

`entity/EmsStationMeta.java`：
```java
package com.energyx.ems.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 收益核算电站投资元数据（ems_station_meta）。station_id 唯一（uk_station_meta_station）。 */
@Data
@TableName("ems_station_meta")
public class EmsStationMeta {

	@TableId(type = IdType.AUTO)
	private Long stationMetaId;

	private Long tenantId;

	private Long stationId;

	/** 投资额 元 */
	private BigDecimal investmentAmount;

	/** 投运日期 */
	private LocalDate installDate;

	@TableField(value = "create_time", fill = FieldFill.INSERT)
	private LocalDateTime createTime;

	@TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
	private LocalDateTime updateTime;

}
```

`mapper/EmsStationMetaMapper.java`：
```java
package com.energyx.ems.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.energyx.ems.entity.EmsStationMeta;

public interface EmsStationMetaMapper extends BaseMapper<EmsStationMeta> {
}
```

`service/EmsStationMetaService.java`：
```java
package com.energyx.ems.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.energyx.common.exception.BusinessException;
import com.energyx.common.exception.ErrorCode;
import com.energyx.common.tenant.TenantContext;
import com.energyx.ems.entity.EmsStationMeta;
import com.energyx.ems.mapper.EmsStationMetaMapper;
import org.springframework.stereotype.Service;

/** 电站投资元数据读写（P1-1 收益核算 ROI 数据源）。upsert 按 station_id 唯一。 */
@Service
public class EmsStationMetaService extends ServiceImpl<EmsStationMetaMapper, EmsStationMeta> {

	/** 查电站投资元数据；未配置返回 null。 */
	public EmsStationMeta getByStation(Long stationId) {
		return getOne(new LambdaQueryWrapper<EmsStationMeta>().eq(EmsStationMeta::getTenantId, requireTenant())
			.eq(EmsStationMeta::getStationId, stationId));
	}

	/** upsert 幂等：同站已存在则原位更新（保留主键），否则插入并补租户。 */
	public EmsStationMeta upsert(EmsStationMeta meta) {
		meta.setTenantId(requireTenant());
		EmsStationMeta hit = getByStation(meta.getStationId());
		if (hit != null) {
			meta.setStationMetaId(hit.getStationMetaId());
			updateById(meta);
			return meta;
		}
		meta.setStationMetaId(null);
		save(meta);
		return meta;
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

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl energy-ems test -Dtest='EmsStationMetaServiceTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（2 条）

- [ ] **Step 5: 格式 + 提交**

Run: `mvn -pl energy-ems spring-javaformat:apply`
Run: `mvn -pl energy-ems validate`
Run:
```bash
git add backend/energy-ems/src/main/resources/db/migration/V5__revenue_meta.sql backend/energy-ems/src/main/java/com/energyx/ems/entity/EmsStationMeta.java backend/energy-ems/src/main/java/com/energyx/ems/mapper/EmsStationMetaMapper.java backend/energy-ems/src/main/java/com/energyx/ems/service/EmsStationMetaService.java backend/energy-ems/src/test/java/com/energyx/ems/service/EmsStationMetaServiceTest.java
git commit -m "feat(ems): P1-1 收益核算电站投资元数据（ems_station_meta 表 + upsert）"
```

---

### Task 4: EmsRevenueService 编排 + 接口层

**Files:**
- Create: `backend/energy-ems/src/main/java/com/energyx/ems/web/dto/RevenueSummary.java`
- Create: `backend/energy-ems/src/main/java/com/energyx/ems/web/dto/RevenueTrendPoint.java`
- Create: `backend/energy-ems/src/main/java/com/energyx/ems/web/dto/RevenueDetailRow.java`
- Create: `backend/energy-ems/src/main/java/com/energyx/ems/web/dto/RevenueMetaReq.java`
- Create: `backend/energy-ems/src/main/java/com/energyx/ems/service/EmsRevenueService.java`
- Create: `backend/energy-ems/src/main/java/com/energyx/ems/web/EmsRevenueController.java`
- Test: `backend/energy-ems/src/test/java/com/energyx/ems/service/EmsRevenueServiceTest.java`

**Interfaces:**
- Consumes: `service/TsdbClient`（Task 1）、`util/RevenueCalculator`（Task 2）、`service/EmsStationMetaService`（Task 3）、`mapper/EmsPlanMapper`/`EmsElectricityPriceMapper`/`PcsDeviceMapper`/`util/TdenginePlanWriter`/`util/PriceTier`/`util/PlanPoint`/`entity/EmsPlan`/`entity/EmsElectricityPrice`/`model/PcsDevice`。
- Produces: `service/EmsRevenueService.summary/trend/detail/meta` + `web/EmsRevenueController` 5 端点（Task 5/6 消费）。

- [ ] **Step 1: 写失败测试 `EmsRevenueServiceTest`**

```java
package com.energyx.ems.service;

import com.energyx.common.tenant.TenantContext;
import com.energyx.common.tenant.TenantInfo;
import com.energyx.ems.entity.EmsElectricityPrice;
import com.energyx.ems.entity.EmsPlan;
import com.energyx.ems.entity.EmsStationMeta;
import com.energyx.ems.mapper.EmsElectricityPriceMapper;
import com.energyx.ems.mapper.EmsPlanMapper;
import com.energyx.ems.mapper.PcsDeviceMapper;
import com.energyx.ems.model.PcsDevice;
import com.energyx.ems.util.TdenginePlanWriter;
import com.energyx.ems.web.dto.RevenueDetailRow;
import com.energyx.ems.web.dto.RevenueSummary;
import com.energyx.ems.web.dto.RevenueTrendPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/** EmsRevenueService 编排：summary/trend/detail/meta 组装、空态、ROI 年化。 */
class EmsRevenueServiceTest {

	private static final LocalDate DAY = LocalDate.of(2026, 8, 1);

	private EmsPlanMapper planMapper;

	private EmsElectricityPriceMapper priceMapper;

	private EmsStationMetaService stationMetaService;

	private PcsDeviceMapper pcsDeviceMapper;

	private TsdbClient tsdbClient;

	private TdenginePlanWriter writer;

	private EmsRevenueService svc;

	@BeforeEach
	void setUp() {
		TenantContext.set(new TenantInfo(7L, 100L));
		planMapper = mock(EmsPlanMapper.class);
		priceMapper = mock(EmsElectricityPriceMapper.class);
		stationMetaService = mock(EmsStationMetaService.class);
		pcsDeviceMapper = mock(PcsDeviceMapper.class);
		tsdbClient = mock(TsdbClient.class);
		writer = mock(TdenginePlanWriter.class);
		svc = new EmsRevenueService(planMapper, priceMapper, stationMetaService, pcsDeviceMapper, tsdbClient, writer);
		ReflectionTestUtils.setField(svc, "productKey", "snd_ess_pcs");
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
	}

	@Test
	void summary_noDevicesReturnsZeros() {
		when(pcsDeviceMapper.selectByStation(anyLong(), anyLong(), any())).thenReturn(List.of());

		RevenueSummary s = svc.summary(10L, "DAY", DAY);

		assertEquals(0.0, s.getTotalEnergy(), 1e-9);
		assertEquals(0.0, s.getArbitrageRevenue(), 1e-9);
		assertEquals(1, s.getDaysCount());
		assertFalse(s.isHasInvestment());
	}

	@Test
	void summary_aggregatesTelemetryWithRunMode() {
		PcsDevice pcs = new PcsDevice(9L, 7L, "snd_ess_pcs", "pcs-1", 2);
		when(pcsDeviceMapper.selectByStation(anyLong(), anyLong(), any())).thenReturn(List.of(pcs));
		when(planMapper.selectList(any())).thenReturn(List.of());
		when(priceMapper.selectList(any())).thenReturn(List.of());
		long start = DAY.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
		// 00:00/00:10 放 60kW（各 10min=10kWh）、00:20/00:30 充 60kW（各 10min），无电价 → 收益 0
		when(tsdbClient.history(anyLong(), any(), any())).thenReturn(List.of(
				new TsdbClient.TelemetryRow(start, 60.0, 2),
				new TsdbClient.TelemetryRow(start + 600_000L, 60.0, 2),
				new TsdbClient.TelemetryRow(start + 1_200_000L, 60.0, 1),
				new TsdbClient.TelemetryRow(start + 1_800_000L, 60.0, 1)));

		RevenueSummary s = svc.summary(10L, "DAY", DAY);

		assertEquals(20.0, s.getDischargeEnergy(), 1e-9);
		assertEquals(10.0, s.getChargeEnergy(), 1e-9);
		assertEquals(0.0, s.getArbitrageRevenue(), 1e-9);
	}

	@Test
	void summary_paybackFromInvestment() {
		PcsDevice pcs = new PcsDevice(9L, 7L, "snd_ess_pcs", "pcs-1", 2);
		when(pcsDeviceMapper.selectByStation(anyLong(), anyLong(), any())).thenReturn(List.of(pcs));
		when(planMapper.selectList(any())).thenReturn(List.of());
		EmsElectricityPrice tier = new EmsElectricityPrice();
		tier.setPriceId(1L);
		tier.setTenantId(7L);
		tier.setStationId(10L);
		tier.setPriceType("PEAK");
		tier.setStartTime(java.time.LocalTime.of(0, 0));
		tier.setEndTime(java.time.LocalTime.of(0, 30));
		tier.setPrice(new BigDecimal("1.0"));
		tier.setValidFrom(DAY);
		tier.setValidTo(DAY);
		tier.setStatus(1);
		when(priceMapper.selectList(any())).thenReturn(List.of(tier));
		long start = DAY.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
		when(tsdbClient.history(anyLong(), any(), any())).thenReturn(List.of(
				new TsdbClient.TelemetryRow(start, 60.0, 2),
				new TsdbClient.TelemetryRow(start + 600_000L, 60.0, 2))); // 10 kWh 放电 @1.0 → 收益 10
		EmsStationMeta meta = new EmsStationMeta();
		meta.setStationId(10L);
		meta.setInvestmentAmount(new BigDecimal("365000"));
		meta.setInstallDate(DAY);
		when(stationMetaService.getByStation(10L)).thenReturn(meta);

		RevenueSummary s = svc.summary(10L, "DAY", DAY);

		assertTrue(s.isHasInvestment());
		assertEquals(10.0, s.getArbitrageRevenue(), 1e-9);
		assertNotNull(s.getPaybackYears()); // 365000 ÷ (10×365) = 100 年
	}

	@Test
	void detail_returnsSlots() throws Exception {
		PcsDevice pcs = new PcsDevice(9L, 7L, "snd_ess_pcs", "pcs-1", 2);
		when(pcsDeviceMapper.selectByStation(anyLong(), anyLong(), any())).thenReturn(List.of(pcs));
		EmsPlan plan = new EmsPlan();
		plan.setPlanId(1L);
		plan.setStationId(10L);
		plan.setPlanDate(DAY);
		plan.setPlanParam("{\"priceDriven\":false,\"dischargeWindows\":[{\"start\":\"00:00\",\"end\":\"01:00\",\"powerLimit\":60}]}");
		when(planMapper.selectList(any())).thenReturn(List.of(plan));
		when(priceMapper.selectList(any())).thenReturn(List.of());
		when(writer.read(anyLong(), any())).thenReturn(List.of(
				new com.energyx.ems.util.PlanPoint(java.time.LocalTime.of(0, 0), "DISCHARGE", 60, 80),
				new com.energyx.ems.util.PlanPoint(java.time.LocalTime.of(0, 10), "DISCHARGE", 60, 80)));
		long start = DAY.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
		when(tsdbClient.history(anyLong(), any(), any())).thenReturn(List.of(
				new TsdbClient.TelemetryRow(start, 60.0, null),
				new TsdbClient.TelemetryRow(start + 600_000L, 60.0, null),
				new TsdbClient.TelemetryRow(start + 1_200_000L, 60.0, null))); // 无 runMode → 回退计划 DISCHARGE；第 3 行末点不计

		List<RevenueDetailRow> rows = svc.detail(10L, DAY);

		assertEquals(2, rows.size());
		assertEquals("DISCHARGE", rows.get(0).getAction());
		assertEquals("PLAN", rows.get(0).getSource());
	}

	@Test
	void trend_monthReturnsDailyPoints() {
		PcsDevice pcs = new PcsDevice(9L, 7L, "snd_ess_pcs", "pcs-1", 2);
		when(pcsDeviceMapper.selectByStation(anyLong(), anyLong(), any())).thenReturn(List.of(pcs));
		when(planMapper.selectList(any())).thenReturn(List.of());
		when(priceMapper.selectList(any())).thenReturn(List.of());
		long start = DAY.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
		when(tsdbClient.history(anyLong(), any(), any()))
			.thenReturn(List.of(new TsdbClient.TelemetryRow(start, 60.0, 2),
					new TsdbClient.TelemetryRow(start + 600_000L, 60.0, 2)));

		List<RevenueTrendPoint> points = svc.trend(10L, "MONTH", DAY);

		assertEquals(31, points.size()); // 8 月 31 天
		assertEquals("08-01", points.get(0).getLabel());
		assertEquals(10.0, points.get(0).getDischargeEnergy(), 1e-9);
	}
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl energy-ems test -Dtest='EmsRevenueServiceTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL（编译错误——类不存在）

- [ ] **Step 3: 写实现**

`web/dto/RevenueSummary.java`：
```java
package com.energyx.ems.web.dto;

import lombok.Data;

import java.math.BigDecimal;

/** 收益核算时段 summary（P1-1）。电量 kWh、金额 元；demandSavings 在 P1-2 前恒 0。 */
@Data
public class RevenueSummary {

	private Long stationId;

	/** DAY/MONTH/YEAR */
	private String periodType;

	private String startDate;

	private String endDate;

	private int daysCount;

	private double chargeEnergy;

	private double dischargeEnergy;

	private double totalEnergy;

	private double arbitrageRevenue;

	private double demandSavings;

	private double totalRevenue;

	/** 投资额 元；未配置为 null */
	private BigDecimal investmentAmount;

	/** 回本周期（年）；投资额未配置或年化收益 ≤0 为 null */
	private Double paybackYears;

	private boolean hasInvestment;

}
```

`web/dto/RevenueTrendPoint.java`：
```java
package com.energyx.ems.web.dto;

import lombok.Data;

/** 收益趋势点（P1-1）。label：月视图 MM-dd、年视图 yyyy-MM。 */
@Data
public class RevenueTrendPoint {

	private String label;

	private double chargeEnergy;

	private double dischargeEnergy;

	private double revenue;

}
```

`web/dto/RevenueDetailRow.java`：
```java
package com.energyx.ems.web.dto;

import lombok.Data;

/** 单日逐槽明细（P1-1）。source：RUN_MODE/PLAN（方向来源）。 */
@Data
public class RevenueDetailRow {

	private String time;

	/** CHARGE/DISCHARGE */
	private String action;

	private double energyKwh;

	private double price;

	private double revenue;

	private String source;

}
```

`web/dto/RevenueMetaReq.java`：
```java
package com.energyx.ems.web.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 电站投资元数据保存请求（P1-1）。 */
@Data
public class RevenueMetaReq {

	private Long stationId;

	/** 投资额 元 */
	private BigDecimal investmentAmount;

	/** 投运日期 */
	private LocalDate installDate;

}
```

`service/EmsRevenueService.java`：
```java
package com.energyx.ems.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.energyx.common.exception.BusinessException;
import com.energyx.common.exception.ErrorCode;
import com.energyx.common.tenant.TenantContext;
import com.energyx.ems.entity.EmsElectricityPrice;
import com.energyx.ems.entity.EmsPlan;
import com.energyx.ems.entity.EmsStationMeta;
import com.energyx.ems.mapper.EmsElectricityPriceMapper;
import com.energyx.ems.mapper.EmsPlanMapper;
import com.energyx.ems.mapper.PcsDeviceMapper;
import com.energyx.ems.model.PcsDevice;
import com.energyx.ems.util.PlanGenerator;
import com.energyx.ems.util.PlanPoint;
import com.energyx.ems.util.PriceTier;
import com.energyx.ems.util.RevenueCalculator;
import com.energyx.ems.util.RevenueDailyResult;
import com.energyx.ems.util.RevenueSlot;
import com.energyx.ems.util.TdenginePlanWriter;
import com.energyx.ems.web.dto.RevenueDetailRow;
import com.energyx.ems.web.dto.RevenueMetaReq;
import com.energyx.ems.web.dto.RevenueSummary;
import com.energyx.ems.web.dto.RevenueTrendPoint;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 收益核算编排（P1-1）：PCS 解析 → 逐日计划/电价查找表 → RevenueCalculator 聚合 → summary/trend/detail。
 * 租户敏感查询（plan/price/PCS 映射器）在主线完成；并行子线程只做 TSDB 拉取 + 纯函数计算（无租户依赖）。
 */
@Slf4j
@Service
public class EmsRevenueService {

	private static final ObjectMapper JSON = new ObjectMapper();

	private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("MM-dd");

	/** PCS 产品标识（与 EmsPlanService.productKey 同一配置） */
	@Value("${energyx.ems.product-key:snd_ess_pcs}")
	private String productKey;

	private final EmsPlanMapper planMapper;

	private final EmsElectricityPriceMapper priceMapper;

	private final EmsStationMetaService stationMetaService;

	private final PcsDeviceMapper pcsDeviceMapper;

	private final TsdbClient tsdbClient;

	private final TdenginePlanWriter writer;

	public EmsRevenueService(EmsPlanMapper planMapper, EmsElectricityPriceMapper priceMapper,
			EmsStationMetaService stationMetaService, PcsDeviceMapper pcsDeviceMapper, TsdbClient tsdbClient,
			TdenginePlanWriter writer) {
		this.planMapper = planMapper;
		this.priceMapper = priceMapper;
		this.stationMetaService = stationMetaService;
		this.pcsDeviceMapper = pcsDeviceMapper;
		this.tsdbClient = tsdbClient;
		this.writer = writer;
	}

	/** 时段收益卡片。 */
	public RevenueSummary summary(Long stationId, String periodType, LocalDate date) {
		LocalDate[] range = resolveRange(periodType, date);
		Map<LocalDate, RevenueDailyResult> daily = dailyResults(stationId, range[0], range[1]);
		RevenueSummary s = new RevenueSummary();
		s.setStationId(stationId);
		s.setPeriodType(periodType);
		s.setStartDate(range[0].toString());
		s.setEndDate(range[1].toString());
		s.setDaysCount((int) ChronoUnit.DAYS.between(range[0], range[1]) + 1);
		double charge = 0;
		double discharge = 0;
		double revenue = 0;
		for (RevenueDailyResult r : daily.values()) {
			charge += r.chargeEnergy();
			discharge += r.dischargeEnergy();
			revenue += r.revenue();
		}
		s.setChargeEnergy(round2(charge));
		s.setDischargeEnergy(round2(discharge));
		s.setTotalEnergy(round2(charge + discharge));
		s.setArbitrageRevenue(round2(revenue));
		s.setDemandSavings(0); // P1-2 前恒 0
		s.setTotalRevenue(round2(revenue));
		fillPayback(s, range[0], range[1]);
		return s;
	}

	/** 趋势曲线：月视图按日、年视图按月。 */
	public List<RevenueTrendPoint> trend(Long stationId, String periodType, LocalDate date) {
		LocalDate[] range = resolveRange(periodType, date);
		Map<LocalDate, RevenueDailyResult> daily = dailyResults(stationId, range[0], range[1]);
		List<Map.Entry<LocalDate, RevenueDailyResult>> sorted = daily.entrySet().stream()
			.sorted(Map.Entry.comparingByKey())
			.toList();
		if ("MONTH".equals(periodType)) {
			return sorted.stream()
				.map(e -> point(e.getKey().format(DAY_LABEL), e.getValue()))
				.toList();
		}
		// YEAR → 按月归并
		Map<YearMonth, double[]> monthly = new LinkedHashMap<>();
		for (Map.Entry<LocalDate, RevenueDailyResult> e : sorted) {
			YearMonth ym = YearMonth.from(e.getKey());
			double[] acc = monthly.computeIfAbsent(ym, k -> new double[3]);
			acc[0] += e.getValue().chargeEnergy();
			acc[1] += e.getValue().dischargeEnergy();
			acc[2] += e.getValue().revenue();
		}
		List<RevenueTrendPoint> out = new ArrayList<>();
		monthly.forEach((ym, acc) -> {
			RevenueTrendPoint p = new RevenueTrendPoint();
			p.setLabel(ym.toString());
			p.setChargeEnergy(round2(acc[0]));
			p.setDischargeEnergy(round2(acc[1]));
			p.setRevenue(round2(acc[2]));
			out.add(p);
		});
		return out;
	}

	/** 单日逐槽明细（按时刻升序）。 */
	public List<RevenueDetailRow> detail(Long stationId, LocalDate date) {
		RevenueDailyResult day = dailyResults(stationId, date, date).get(date);
		if (day == null) {
			return List.of();
		}
		List<RevenueDetailRow> rows = new ArrayList<>();
		for (RevenueSlot slot : day.slots()) {
			RevenueDetailRow r = new RevenueDetailRow();
			r.setTime(slot.time().toString());
			r.setAction(slot.action());
			r.setEnergyKwh(round2(slot.energyKwh()));
			r.setPrice(slot.price());
			r.setRevenue(round2(slot.revenue()));
			r.setSource(slot.source());
			rows.add(r);
		}
		rows.sort(Comparator.comparing(RevenueDetailRow::getTime));
		return rows;
	}

	/** 查电站投资元数据；未配置返回 null。 */
	public EmsStationMeta meta(Long stationId) {
		return stationMetaService.getByStation(stationId);
	}

	/** 保存电站投资元数据（upsert）。 */
	public EmsStationMeta saveMeta(RevenueMetaReq req) {
		if (req.getStationId() == null) {
			throw new BusinessException(ErrorCode.PARAM_MISSING, "stationId 必填");
		}
		EmsStationMeta meta = new EmsStationMeta();
		meta.setStationId(req.getStationId());
		meta.setInvestmentAmount(req.getInvestmentAmount());
		meta.setInstallDate(req.getInstallDate());
		return stationMetaService.upsert(meta);
	}

	/** 逐日聚合（跨设备合并）。租户敏感查询在此方法主线完成；设备并行只做 TSDB+纯计算。 */
	private Map<LocalDate, RevenueDailyResult> dailyResults(Long stationId, LocalDate start, LocalDate end) {
		Long tenant = requireTenant();
		Map<LocalDate, RevenueDailyResult> out = new LinkedHashMap<>();
		List<PcsDevice> devices = pcsDeviceMapper.selectByStation(tenant, stationId, productKey);
		if (devices == null || devices.isEmpty()) {
			return out;
		}
		List<EmsPlan> plans = planMapper.selectList(new LambdaQueryWrapper<EmsPlan>()
			.eq(EmsPlan::getStationId, stationId)
			.between(EmsPlan::getPlanDate, start, end));
		Map<LocalDate, EmsPlan> planByDate = plans.stream()
			.collect(Collectors.toMap(EmsPlan::getPlanDate, p -> p));
		List<EmsElectricityPrice> priceRows = priceMapper.selectList(new LambdaQueryWrapper<EmsElectricityPrice>()
			.eq(EmsElectricityPrice::getTenantId, tenant)
			.eq(EmsElectricityPrice::getStationId, stationId)
			.eq(EmsElectricityPrice::getStatus, 1)
			.le(EmsElectricityPrice::getValidFrom, end)
			.ge(EmsElectricityPrice::getValidTo, start));
		for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
			EmsPlan plan = planByDate.get(d);
			Function<LocalTime, String> planAction = planActionLookup(plan);
			Function<LocalTime, Double> price = buildPriceLookup(priceLookupFor(priceRows, plan, d));
			List<RevenueDailyResult> dayResults = devices.parallelStream()
				.map(dev -> RevenueCalculator.aggregateDay(d,
						tsdbClient.history(dev.deviceId(), dev.productKey(), d), planAction, price))
				.filter(java.util.Objects::nonNull)
				.toList();
			double charge = 0;
			double discharge = 0;
			double revenue = 0;
			List<RevenueSlot> slots = new ArrayList<>();
			for (RevenueDailyResult r : dayResults) {
				charge += r.chargeEnergy();
				discharge += r.dischargeEnergy();
				revenue += r.revenue();
				slots.addAll(r.slots());
			}
			out.put(d, new RevenueDailyResult(d, charge, discharge, revenue, slots));
		}
		return out;
	}

	/** 当日电价档：电价驱动计划有 priceSnapshot → 快照；否则 → 电价表（status=1 且有效期覆盖该日）。 */
	private static List<PriceTier> priceLookupFor(List<EmsElectricityPrice> rows, EmsPlan plan, LocalDate date) {
		if (plan != null && isPriceDriven(plan.getPlanParam())) {
			List<PriceTier> snapshot = parseSnapshot(plan.getPlanParam());
			if (!snapshot.isEmpty()) {
				return snapshot;
			}
		}
		return rows.stream()
			.filter(p -> !p.getValidFrom().isAfter(date) && !p.getValidTo().isBefore(date))
			.map(p -> new PriceTier(p.getStartTime(), p.getEndTime(), p.getPriceType(), p.getPrice().doubleValue()))
			.toList();
	}

	/** 计划动作查找：计划点 → 5min 槽时刻→动作（遥测时刻向下取整匹配）。无可用动作返回 null。 */
	private Function<LocalTime, String> planActionLookup(EmsPlan plan) {
		if (plan == null) {
			return null;
		}
		try {
			List<PlanPoint> points = writer.read(plan.getStationId(), plan.getPlanDate());
			Map<LocalTime, String> bySlot = new HashMap<>();
			for (PlanPoint p : points) {
				if ("CHARGE".equals(p.action()) || "DISCHARGE".equals(p.action())) {
					bySlot.put(p.time(), p.action());
				}
			}
			if (bySlot.isEmpty()) {
				return null;
			}
			return t -> bySlot.get(floorToSlot(t));
		}
		catch (Exception e) {
			return null;
		}
	}

	/** 时刻向下取整到 5min 槽（计划点粒度）。 */
	private static LocalTime floorToSlot(LocalTime t) {
		return LocalTime.of(t.getHour(), t.getMinute() / PlanGenerator.SLOT_MIN * PlanGenerator.SLOT_MIN);
	}

	/** 档位列表 → 时刻→电价 查找函数（区间 [start, end) 匹配；未覆盖返回 null）。 */
	private static Function<LocalTime, Double> buildPriceLookup(List<PriceTier> tiers) {
		List<PriceTier> sorted = tiers.stream().sorted(Comparator.comparing(PriceTier::start)).toList();
		if (sorted.isEmpty()) {
			return null;
		}
		return t -> {
			for (PriceTier tier : sorted) {
				if (!t.isBefore(tier.start()) && t.isBefore(tier.end())) {
					return tier.price();
				}
			}
			return null;
		};
	}

	private static boolean isPriceDriven(String planParam) {
		try {
			return JSON.readTree(planParam).path("priceDriven").asBoolean(false);
		}
		catch (Exception e) {
			return false;
		}
	}

	/** 解析 plan_param.priceSnapshot（[{priceType,start,end,price}]）。 */
	private static List<PriceTier> parseSnapshot(String planParam) {
		try {
			JsonNode snap = JSON.readTree(planParam).path("priceSnapshot");
			List<PriceTier> out = new ArrayList<>();
			for (JsonNode tier : snap) {
				out.add(new PriceTier(LocalTime.parse(tier.path("start").asText()),
						LocalTime.parse(tier.path("end").asText()), tier.path("priceType").asText(),
						tier.path("price").asDouble()));
			}
			return out;
		}
		catch (Exception e) {
			return List.of();
		}
	}

	/** ROI：投资额 ÷ 年化收益；年化按投运日期截断（投运前天数不计入回本核算）。 */
	private void fillPayback(RevenueSummary s, LocalDate start, LocalDate end) {
		EmsStationMeta meta = stationMetaService.getByStation(s.getStationId());
		boolean has = meta != null && meta.getInvestmentAmount() != null && meta.getInvestmentAmount().signum() > 0;
		s.setHasInvestment(has);
		if (!has) {
			return;
		}
		s.setInvestmentAmount(meta.getInvestmentAmount());
		LocalDate effectiveStart = meta.getInstallDate() != null && meta.getInstallDate().isAfter(start)
				? meta.getInstallDate() : start;
		long days = ChronoUnit.DAYS.between(effectiveStart, end) + 1;
		if (days > 0 && s.getTotalRevenue() > 0) {
			double annual = s.getTotalRevenue() * 365.0 / days;
			s.setPaybackYears(round2(meta.getInvestmentAmount().doubleValue() / annual));
		}
	}

	private static LocalDate[] resolveRange(String periodType, LocalDate date) {
		return switch (periodType) {
			case "DAY" -> new LocalDate[] { date, date };
			case "MONTH" -> new LocalDate[] { date.withDayOfMonth(1), date.withDayOfMonth(date.lengthOfMonth()) };
			case "YEAR" -> new LocalDate[] { date.withDayOfYear(1), date.withDayOfYear(date.lengthOfYear()) };
			default -> throw new BusinessException(ErrorCode.PARAM_INVALID, "periodType 仅支持 DAY/MONTH/YEAR");
		};
	}

	private static RevenueTrendPoint point(String label, RevenueDailyResult r) {
		RevenueTrendPoint p = new RevenueTrendPoint();
		p.setLabel(label);
		p.setChargeEnergy(round2(r.chargeEnergy()));
		p.setDischargeEnergy(round2(r.dischargeEnergy()));
		p.setRevenue(round2(r.revenue()));
		return p;
	}

	private static double round2(double v) {
		return Math.round(v * 100.0) / 100.0;
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

`web/EmsRevenueController.java`：
```java
package com.energyx.ems.web;

import com.energyx.common.model.Result;
import com.energyx.ems.entity.EmsStationMeta;
import com.energyx.ems.service.EmsRevenueService;
import com.energyx.ems.web.dto.RevenueDetailRow;
import com.energyx.ems.web.dto.RevenueMetaReq;
import com.energyx.ems.web.dto.RevenueSummary;
import com.energyx.ems.web.dto.RevenueTrendPoint;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** 收益核算接口（P1-1）。网关 /api/ems/** → energy-ems，控制器映射带 /ems。 */
@RestController
@RequestMapping("/ems/revenue")
public class EmsRevenueController {

	private final EmsRevenueService service;

	public EmsRevenueController(EmsRevenueService service) {
		this.service = service;
	}

	@GetMapping("/summary")
	public Result<RevenueSummary> summary(@RequestParam Long stationId, @RequestParam String periodType,
			@RequestParam LocalDate date) {
		return Result.ok(service.summary(stationId, periodType, date));
	}

	@GetMapping("/trend")
	public Result<List<RevenueTrendPoint>> trend(@RequestParam Long stationId, @RequestParam String periodType,
			@RequestParam LocalDate date) {
		return Result.ok(service.trend(stationId, periodType, date));
	}

	@GetMapping("/detail")
	public Result<List<RevenueDetailRow>> detail(@RequestParam Long stationId, @RequestParam LocalDate date) {
		return Result.ok(service.detail(stationId, date));
	}

	@GetMapping("/meta")
	public Result<EmsStationMeta> meta(@RequestParam Long stationId) {
		return Result.ok(service.meta(stationId));
	}

	@PutMapping("/meta")
	public Result<EmsStationMeta> saveMeta(@RequestBody RevenueMetaReq req) {
		return Result.ok(service.saveMeta(req));
	}

}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl energy-ems test -Dtest='EmsRevenueServiceTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（5 条）

- [ ] **Step 5: 全模块回归 + 格式 + 提交**

Run: `mvn -pl energy-ems test`
Run: `mvn -pl energy-ems spring-javaformat:apply`
Run: `mvn -pl energy-ems validate`
Run:
```bash
git add backend/energy-ems/src/main/java/com/energyx/ems/web/dto/RevenueSummary.java backend/energy-ems/src/main/java/com/energyx/ems/web/dto/RevenueTrendPoint.java backend/energy-ems/src/main/java/com/energyx/ems/web/dto/RevenueDetailRow.java backend/energy-ems/src/main/java/com/energyx/ems/web/dto/RevenueMetaReq.java backend/energy-ems/src/main/java/com/energyx/ems/service/EmsRevenueService.java backend/energy-ems/src/main/java/com/energyx/ems/web/EmsRevenueController.java backend/energy-ems/src/test/java/com/energyx/ems/service/EmsRevenueServiceTest.java
git commit -m "feat(ems): P1-1 收益核算接口层（summary/trend/detail/meta 编排 + 5 端点）"
```

---

### Task 5: 前端类型与 API 方法

**Files:**
- Modify: `frontend/src/types/models.ts`（追加 4 个接口）
- Modify: `frontend/src/api/ems.ts`（追加 5 个方法）
- Test: 前端类型检查（`vue-tsc --noEmit`，package.json 脚本确认）

**Interfaces:**
- Consumes: 后端 5 端点契约（Task 4 的 DTO 字段名，`primitive long/double` 数字、`Long` 序列化为字符串）。
- Produces: `emsApi.revenueSummary/revenueTrend/revenueDetail/revenueMetaGet/revenueMetaPut`、`RevenueSummary/RevenueTrendPoint/RevenueDetailRow/EmsStationMeta` 类型。Task 6 消费。

- [ ] **Step 1: 追加类型到 `frontend/src/types/models.ts`**（追加到 `EmsElectricityPrice` 接口之后、`// ---------------- 电站 Station ----------------` 之前）

```ts
// ---------------- 收益核算 Revenue (P1-1) ----------------

export interface RevenueSummary {
  stationId: string
  /** DAY/MONTH/YEAR */
  periodType: string
  startDate: string
  endDate: string
  daysCount: number
  chargeEnergy: number
  dischargeEnergy: number
  totalEnergy: number
  arbitrageRevenue: number
  /** P1-2 前恒 0 */
  demandSavings: number
  totalRevenue: number
  investmentAmount: number | null
  paybackYears: number | null
  hasInvestment: boolean
}

export interface RevenueTrendPoint {
  /** 月视图 MM-dd、年视图 yyyy-MM */
  label: string
  chargeEnergy: number
  dischargeEnergy: number
  revenue: number
}

export interface RevenueDetailRow {
  time: string
  action: string
  energyKwh: number
  price: number
  revenue: number
  /** RUN_MODE/PLAN */
  source: string
}

export interface EmsStationMeta {
  stationMetaId: string
  stationId: string
  investmentAmount: number | null
  installDate: string | null
  createTime?: string
  updateTime?: string
}
```

- [ ] **Step 2: 追加方法到 `frontend/src/api/ems.ts`**（import 追加类型，`dispatch` 方法之后追加）

```ts
  /** GET /api/ems/revenue/summary 时段收益卡片 */
  revenueSummary(params: Record<string, unknown>): Promise<RevenueSummary> { return http.get('/api/ems/revenue/summary', { params }) },

  /** GET /api/ems/revenue/trend 收益趋势曲线（月按日、年按月） */
  revenueTrend(params: Record<string, unknown>): Promise<RevenueTrendPoint[]> { return http.get('/api/ems/revenue/trend', { params }) },

  /** GET /api/ems/revenue/detail 单日逐槽明细 */
  revenueDetail(params: Record<string, unknown>): Promise<RevenueDetailRow[]> { return http.get('/api/ems/revenue/detail', { params }) },

  /** GET /api/ems/revenue/meta 电站投资元数据（未配置返回 null） */
  revenueMetaGet(stationId: string): Promise<EmsStationMeta | null> { return http.get('/api/ems/revenue/meta', { params: { stationId } }) },

  /** PUT /api/ems/revenue/meta 保存电站投资元数据 */
  revenueMetaPut(body: Partial<EmsStationMeta>): Promise<EmsStationMeta> { return http.put('/api/ems/revenue/meta', body) },
```

import 行更新为：
```ts
import type { EmsStrategy, EmsPlan, EmsPlanPoint, EmsExecutionRecord, EmsConstraint, EmsElectricityPrice, PageResult, RevenueSummary, RevenueTrendPoint, RevenueDetailRow, EmsStationMeta } from '@/types/models'
```

- [ ] **Step 3: 前端类型检查**

Run（在 `frontend/` 目录）：`npm run type-check`（若 package.json 无此脚本则 `npx vue-tsc --noEmit`）
Expected: PASS（无类型错误）

- [ ] **Step 4: 提交**

```bash
git add frontend/src/types/models.ts frontend/src/api/ems.ts
git commit -m "feat(ems-frontend): P1-1 收益核算前端类型与 API 方法"
```

---

### Task 6: 前端收益页 `/ems/revenue`

**Files:**
- Create: `frontend/src/views/EmsRevenue.vue`
- Modify: `frontend/src/router/index.ts`（注册路由）
- Test: 前端类型检查 + `npm run build` + 既有 vitest 全绿

**Interfaces:**
- Consumes: `emsApi.revenueSummary/revenueTrend/revenueDetail/revenueMetaGet/revenueMetaPut`（Task 5）、`loadStations`/`stationName`（`utils/stationDict`）、`priceTypeTag`/`priceTypeText`（`utils/dicts`）。
- Produces: 路由 `/ems/revenue` 收益核算页。

- [ ] **Step 1: 创建 `frontend/src/views/EmsRevenue.vue`**

页面结构（复用 EmsPrice.vue 的电站选择 + EmsPlan.vue 的图表/电价底纹模式）：

**script setup 核心逻辑**：
```ts
<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { emsApi } from '@/api/ems'
import type { EmsStationMeta, RevenueDetailRow, RevenueSummary, RevenueTrendPoint, Station } from '@/types/models'
import { loadStations } from '@/utils/stationDict'

const route = useRoute()

const stations = ref<Station[]>([])
const stationId = ref('')
const periodType = ref<'DAY' | 'MONTH' | 'YEAR'>('DAY')
const date = ref(todayStr())
const loading = ref(false)
const summary = ref<RevenueSummary | null>(null)
const trend = ref<RevenueTrendPoint[]>([])
const detail = ref<RevenueDetailRow[]>([])
const meta = ref<EmsStationMeta | null>(null)
const chartEl = ref<HTMLElement | null>(null)

const PERIODS = [
  { key: 'DAY', label: '日' },
  { key: 'MONTH', label: '月' },
  { key: 'YEAR', label: '年' },
] as const

function todayStr(): string {
  const d = new Date()
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

/** 当前维度下的有效日期范围（日→当天、月→当月、年→当年），供 el-date-picker 约束 */
const dateRange = computed(() => {
  if (!date.value) return [undefined, undefined] as [string | undefined, string | undefined]
  const [y, m] = date.value.split('-').map(Number)
  if (periodType.value === 'DAY') return [date.value, date.value]
  if (periodType.value === 'MONTH') {
    const last = new Date(y, m, 0).getDate()
    return [`${y}-${String(m).padStart(2, '0')}-01`, `${y}-${String(m).padStart(2, '0')}-${String(last).padStart(2, '0')}`]
  }
  return [`${y}-01-01`, `${y}-12-31`]
})

async function load(): Promise<void> {
  if (!stationId.value) {
    summary.value = null
    trend.value = []
    detail.value = []
    return
  }
  loading.value = true
  try {
    const params = { stationId: stationId.value, periodType: periodType.value, date: date.value }
    const [s, t, d] = await Promise.all([
      emsApi.revenueSummary(params),
      periodType.value === 'DAY' ? Promise.resolve([] as RevenueTrendPoint[]) : emsApi.revenueTrend(params),
      periodType.value === 'DAY' ? emsApi.revenueDetail({ stationId: stationId.value, date: date.value }) : Promise.resolve([] as RevenueDetailRow[]),
    ])
    summary.value = s
    trend.value = t
    detail.value = d
    meta.value = await emsApi.revenueMetaGet(stationId.value)
    renderChart()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    loading.value = false
  }
}

function renderChart(): void { /* 见 Step 3 图表配置 */ }

function fmtKwh(v: number | null | undefined): string {
  return v == null ? '—' : `${v.toFixed(1)} kWh`
}

function fmtYuan(v: number | null | undefined): string {
  return v == null ? '—' : `¥${v.toFixed(2)}`
}
</script>
```

**template 结构**：
```html
<template>
  <div class="ex-page">
    <header class="ex-page-head">
      <div class="head-title">
        <h1 class="ex-title">收益核算</h1>
        <p class="ex-sub">实际遥测口径 · 峰谷套利收益 = 放电电量×峰价 − 充电电量×谷价 · 需量节省待 P1-2</p>
      </div>
      <el-button v-if="stationId" type="primary" @click="openMeta">设置投资额</el-button>
    </header>

    <section class="ex-card filter-card">
      <el-form inline @submit.prevent>
        <el-form-item label="电站">
          <el-select v-model="stationId" placeholder="选择电站" filterable clearable style="width: 260px" @change="onChange">
            <el-option v-for="s in stations" :key="s.stationId" :label="s.stationName" :value="s.stationId" />
          </el-select>
        </el-form-item>
        <el-form-item label="维度">
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
      <div class="kpi"><span class="kpi-label">充电量</span><span class="kpi-num">{{ fmtKwh(summary?.chargeEnergy) }}</span></div>
      <div class="kpi"><span class="kpi-label">放电量</span><span class="kpi-num">{{ fmtKwh(summary?.dischargeEnergy) }}</span></div>
      <div class="kpi"><span class="kpi-label">总电量</span><span class="kpi-num">{{ fmtKwh(summary?.totalEnergy) }}</span></div>
      <div class="kpi"><span class="kpi-label">套利收益</span><span class="kpi-num">{{ fmtYuan(summary?.arbitrageRevenue) }}</span></div>
      <div class="kpi"><span class="kpi-label">需量节省</span><span class="kpi-num">—</span></div>
      <div class="kpi"><span class="kpi-label">累计收益</span><span class="kpi-num">{{ fmtYuan(summary?.totalRevenue) }}</span></div>
      <div class="kpi kpi-roi"><span class="kpi-label">回本周期</span>
        <span class="kpi-num">{{ summary?.hasInvestment ? (summary.paybackYears == null ? '—' : summary.paybackYears + ' 年') : '未设置投资额' }}</span>
      </div>
    </section>

    <section class="ex-card chart-card">
      <div ref="chartEl" class="chart" role="img" aria-label="收益趋势曲线"></div>
    </section>

    <section v-if="periodType === 'DAY'" class="ex-card table-card">
      <h3 class="ex-section">单日逐槽明细</h3>
      <el-table :data="detail" size="small" empty-text="该日无遥测或方向均未知">
        <el-table-column prop="time" label="时刻" width="90" />
        <el-table-column prop="action" label="方向" width="100" />
        <el-table-column prop="energyKwh" label="能量 (kWh)" align="right" />
        <el-table-column prop="price" label="电价 (元/kWh)" align="right" />
        <el-table-column prop="revenue" label="收益 (元)" align="right" />
        <el-table-column prop="source" label="方向来源" width="110" />
      </el-table>
    </section>

    <el-dialog v-model="metaVisible" title="设置投资额" width="440px">
      <el-form label-width="100px" @submit.prevent>
        <el-form-item label="投资额 (元)" required>
          <el-input-number v-model="metaForm.investmentAmount" :min="0" :precision="0" :step="10000" style="width: 100%" />
        </el-form-item>
        <el-form-item label="投运日期">
          <el-date-picker v-model="metaForm.installDate" type="date" value-format="YYYY-MM-DD" placeholder="投运日期" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="metaVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingMeta" @click="saveMeta">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
```

- [ ] **Step 2: script 剩余逻辑**（`onChange`、`openMeta`、`saveMeta`、`onMounted`）

```ts
const metaVisible = ref(false)
const savingMeta = ref(false)
const metaForm = reactive<{ investmentAmount: number | null; installDate: string | null }>({ investmentAmount: null, installDate: null })

function onChange(): void { void load() }

function openMeta(): void {
  metaForm.investmentAmount = meta.value?.investmentAmount ?? null
  metaForm.installDate = meta.value?.installDate ?? null
  metaVisible.value = true
}

async function saveMeta(): Promise<void> {
  if (metaForm.investmentAmount == null || metaForm.investmentAmount <= 0) {
    ElMessage.warning('请输入投资额（大于 0）')
    return
  }
  savingMeta.value = true
  try {
    await emsApi.revenueMetaPut({ stationId: stationId.value, investmentAmount: metaForm.investmentAmount, installDate: metaForm.installDate ?? undefined })
    ElMessage.success('已保存')
    metaVisible.value = false
    void load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    savingMeta.value = false
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
```

- [ ] **Step 3: `renderChart()` 图表配置**

```ts
import * as echarts from 'echarts'
// 顶部 import 追加

function renderChart(): void {
  const el = chartEl.value
  if (!el) return
  const chart = echarts.getInstanceByDom(el) ?? echarts.init(el)
  const isDay = periodType.value === 'DAY'
  const xData = isDay ? detail.value.map((r) => r.time) : trend.value.map((t) => t.label)
  const charge = isDay ? detail.value.filter((r) => r.action === 'CHARGE').map((r) => r.energyKwh) : trend.value.map((t) => t.chargeEnergy)
  const discharge = isDay ? detail.value.filter((r) => r.action === 'DISCHARGE').map((r) => r.energyKwh) : trend.value.map((t) => t.dischargeEnergy)
  const revenue = isDay ? detail.value.map((r) => r.revenue) : trend.value.map((t) => t.revenue)
  chart.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: ['充电量', '放电量', '收益'] },
    grid: { left: 60, right: 60, top: 40, bottom: 40 },
    xAxis: { type: 'category', data: xData, boundaryGap: true },
    yAxis: [{ type: 'value', name: 'kWh' }, { type: 'value', name: '元' }],
    series: [
      { name: '充电量', type: 'bar', stack: 'energy', data: charge, itemStyle: { color: '#3b82f6' } },
      { name: '放电量', type: 'bar', stack: 'energy', data: discharge, itemStyle: { color: '#f59e0b' } },
      { name: '收益', type: 'line', yAxisIndex: 1, data: revenue, smooth: true, itemStyle: { color: '#10b981' } },
    ],
  })
}
```

注意：日视图的 detail 逐槽行时间粒度与柱宽不匹配问题——日视图直接展示逐槽能量柱（detail 行即槽位），量级小时直接展示；趋势数据为 0 时图仍渲染空轴。

- [ ] **Step 4: 注册路由** `frontend/src/router/index.ts`（`EmsPlan` 路由之后插入）

```ts
      {
        path: 'ems/revenue',
        name: 'EmsRevenue',
        component: () => import('@/views/EmsRevenue.vue'),
        meta: { title: '收益核算', icon: 'Money' },
      },
```

- [ ] **Step 5: 类型检查 + 构建 + 测试**

Run（在 `frontend/` 目录）：
- `npm run type-check`（若无则 `npx vue-tsc --noEmit`）
- `npm run build`
- `npx vitest run`
Expected: 全部 PASS

- [ ] **Step 6: 提交**

```bash
git add frontend/src/views/EmsRevenue.vue frontend/src/router/index.ts
git commit -m "feat(ems-frontend): P1-1 收益核算页面（KPI 卡片 + 趋势图表 + 投资额设置）"
```

---

## Self-Review 记录

**Spec 覆盖：**
- §3.1 五个端点 → Task 4（Controller + DTO）+ Task 5/6（前端消费）✅
- §3.2 聚合语义（遥测积分/dt 钳制/方向 runMode 优先回退计划/电价快照优先回退表/收益符号）→ Task 2 + Task 4 `priceLookupFor`/`planActionLookup` ✅
- §3.3 文件清单 → Task 1-4 ✅
- §3.4 前端 → Task 5-6 ✅
- §4 测试（RevenueCalculatorTest/EmsRevenueServiceTest/EmsStationMetaServiceTest）→ Task 2/4/3 ✅
- 非目标（需量 0、预聚合不做、不改 tsdb、无缓存）→ 设计内未实现 ✅

**占位符扫描：** 无 TBD/TODO；所有代码块为完整实现。✅

**类型一致性：**
- `TsdbClient.TelemetryRow(long ts, Double power, Integer runMode)` 在 Task 1 定义、Task 2 消费，签名一致 ✅
- `RevenueDailyResult`/`RevenueSlot` 在 Task 2 定义、Task 4 消费 ✅
- `EmsStationMetaService.getByStation/upsert` 在 Task 3 定义、Task 4 消费 ✅
- 前端类型字段与后端 DTO（RevenueSummary/RevenueTrendPoint/RevenueDetailRow/EmsStationMeta）字段名逐一对齐 ✅
- `RevenueCalculator.aggregateDay` 入参顺序（date, rows, planAction, price）Task 2 定义与 Task 4 调用一致 ✅

**评审修复（Task 1 review 后）：** 中分页失败分支 `break` → `return List.of()`（Global Constraint「任一步失败返回空列表」原代码自相矛盾），并补 3 条回归测试（page2 失败/null Result/null records）。

**评审修复（Task 2 实现代理发现）：** 末采样点无后继间隔不计（设计决定），原 Task 2 两测试与 Task 4 `detail_returnsSlots` 假设末行也产生槽——补末采样点行（`row(20)`/第三行遥测）使断言与语义一致。
