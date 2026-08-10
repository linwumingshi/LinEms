# 充放电计划页重设计 + 分布式调度 xxl-job 迁移 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** ① 充放电计划页改列表+Tab 分栏布局并叠加实际功率曲线；② 7 个低频 cron 定时任务迁移 xxl-job。

**Architecture:**
- Part A：`EmsPlan.vue` 整体重构——左侧计划列表（名称化+筛选），右侧主区 Tab（波形/执行记录）；波形用 ECharts 叠加 TSDB 实际功率虚线。
- Part B：新增 xxl-job-admin 容器 + 7 个模块接入 xxl-job-core 执行器，7 处低频 `@Scheduled` 改 `@XxlJob` 注解；4 个秒级内部循环保留本地 @Scheduled。

**Tech Stack:** Vue3 + Element Plus + ECharts（前端）；Spring Boot 3.5 + xxl-job-core 2.4.x + Docker（后端）

## Global Constraints

- 后端格式化必须 `mvn spring-javaformat:apply`（validate 已挂构建，改代码后必须格式化）
- 关键代码加中文注释；方法签名变化同步 Javadoc；禁行尾注释；遵循阿里巴巴编码规范
- Maven 用 PowerShell 调 `D:\Program Files\Maven\bin\mvn.cmd`，离线 `-o`，本地仓库 `-Dmaven.repo.local=D:\Program Files\maven-repo`（参数整体单引号包裹）
- 起服务前必须 `Remove-Item Env:SERVER__PORT, Env:SERVER__HOST`；Start-Process 的 jar 路径必须带引号
- 前端类型检查 `vue-tsc --noEmit`；EmsPlan.vue 遵循 `styles/main.css` 仪表仪器风 token（--ex-*）
- Redis key 新增必须先补 `docs/design/Redis-key规范.md`
- xxl-job 迁移的 7 个任务**保留原有 DistributedLock 逻辑**（防 admin 双活/重试重叠）

---

## Part A：充放电计划页重设计（前端，独立交付）

### Task 1: 前端 TSDB/设备数据访问层补充

**Files:**
- Modify: `frontend/src/api/tsdb.ts`（已有 `propertyHistory`，无需改）
- Modify: `frontend/src/types/models.ts`（补 `PropertyHistoryView` 字段类型，若缺）

**Interfaces:**
- Consumes: 现有 `tsdbApi.propertyHistory({deviceId, productKey, identifiers, startTime, endTime, order, page, size})` → `Promise<PropertyHistoryView>`
- Consumes: 现有 `deviceApi.page({pageNum, pageSize, deviceName})` → `PageResult<Device>`（反查 deviceId）
- Produces: `frontend/src/utils/planCurve.ts` 导出 `fetchActualCurve(deviceId, productKey, planDate)` 返回 `{ times: string[], power: number[] }`（asc 排序，供波形叠加）

- [ ] **Step 1: 检查 models.ts 是否已有 PropertyHistoryView 类型定义**

Run: Grep `PropertyHistoryView` in `frontend/src/types/models.ts`
Expected: 存在则跳过；不存在则按后端 `TdengineQueryService.queryHistory` 返回结构补：
```ts
export interface PropertyHistoryView {
  deviceId: string
  productKey: string
  identifiers: string[]
  startTime: number
  endTime: number
  order: 'asc' | 'desc'
  total: number
  /** 每条: { ts: number; [identifier]: number } 按时间排序 */
  rows: Array<Record<string, number>>
}
```

- [ ] **Step 2: 新建 planCurve.ts 实际曲线工具**

Create: `frontend/src/utils/planCurve.ts`
```ts
import { tsdbApi } from '@/api/tsdb'

/** 拉取计划日实际功率曲线（TSDB property/history，asc 排序）；失败抛错由调用方降级 */
export async function fetchActualCurve(
  deviceId: string,
  productKey: string,
  planDate: string, // YYYY-MM-DD
): Promise<{ times: string[]; power: number[] }> {
  const start = new Date(`${planDate}T00:00:00`).getTime()
  const end = new Date(`${planDate}T23:59:59`).getTime()
  const view = await tsdbApi.propertyHistory({
    deviceId,
    productKey,
    identifiers: ['power'],
    startTime: start,
    endTime: end,
    order: 'asc',
    page: 1,
    size: 2000,
  })
  const times: string[] = []
  const power: number[] = []
  for (const row of view.rows) {
    const ts = typeof row.ts === 'number' ? row.ts : Date.parse(String(row.ts))
    if (Number.isNaN(ts)) continue
    times.push(new Date(ts).toTimeString().slice(0, 5))
    power.push(Number(row.power ?? 0))
  }
  return { times, power }
}
```

- [ ] **Step 3: vue-tsc 类型检查**

Run: `cd frontend && ./node_modules/.bin/vue-tsc --noEmit`
Expected: PASS（无新错误）

- [ ] **Step 4: Commit**

```bash
git add frontend/src/utils/planCurve.ts frontend/src/types/models.ts
git commit -m "feat(frontend): 实际功率曲线数据访问层（TSDB property/history）"
```

### Task 2: EmsPlan.vue 列表+Tab 分栏重构（布局骨架 + 名称化 + 筛选）

**Files:**
- Modify: `frontend/src/views/EmsPlan.vue`（整体重构 template + script）
- Test: `frontend/src/views/__tests__/EmsPlan.spec.ts`（若测试目录存在；不存在则跳过单测，靠 vue-tsc + 冒烟）

**Interfaces:**
- Consumes: `emsApi.planPage({pageNo,pageSize,stationId,status})`（后端已支持 stationId；status 过滤需确认后端 `EmsPlanService.page` 增加 status 参数——见 Task 3）
- Consumes: `loadStations()` / `stationName()`（已有 stationDict）；`emsApi.strategyPage`（策略名称映射）
- Produces: 重构后的 `EmsPlan.vue`，包含：左侧列表区（`el-table` + 电站下拉 + 状态下拉 + 分页）、右侧 `el-tabs`（Tab1 波形 / Tab2 执行记录）

- [ ] **Step 1: 确认后端 planPage 是否支持 status 筛选**

Run: Grep `EmsPlanService.page` 签名
Expected: 当前为 `page(pageNo, pageSize, stationId)`——**无 status**。记入 Task 3 后端改造。

- [ ] **Step 2: 重构 EmsPlan.vue 骨架**

Modify: `frontend/src/views/EmsPlan.vue`，模板改为：
```
<div class="plan-layout">            <!-- flex: 左列表 360px + 右主区 1fr -->
  <aside class="plan-list">
    <div class="filter-bar">
      <el-select v-model="filters.stationId" 电站下拉 clearable @change="load" />
      <el-select v-model="filters.status" 状态下拉 clearable @change="load" />
    </div>
    <el-table :data="list" highlight-current-row @row-click="onRowClick" :row-class-name="rowClassName">
      日期 / 电站名(stationName) / 策略名(strategyLabel) / 总量 / 状态Tag / 操作(查看·下发)
    </el-table>
    <el-pagination v-model:current-page="pageNo" v-model:page-size="pageSize" :total="total" @change="load" />
  </aside>
  <main class="plan-main">
    <header class="plan-head">副题：电站名 · 策略名 · 计划日期 + 生成计划/下发按钮</header>
    <el-tabs v-model="activeTab">
      <el-tab-pane label="计划波形" name="wave">  <!-- 复用现有 renderChart + 实际曲线叠加(Task 5) --> </el-tab-pane>
      <el-tab-pane label="执行记录" name="exec">  <!-- 复用现有 exec 表格 --> </el-tab-pane>
    </el-tabs>
  </main>
</div>
```
script 补充：`filters = ref<{stationId?: string; status?: number}>`；`load()` 传 `{...filters}`；`strategyLabel` 已有；`stationName` 已有。
样式：复用 `--ex-*` token；列表区白卡 + 右侧主区白卡，`gap: 14px`。

- [ ] **Step 3: vue-tsc 类型检查**

Run: `cd frontend && ./node_modules/.bin/vue-tsc --noEmit`
Expected: PASS

- [ ] **Step 4: 提交**

```bash
git add frontend/src/views/EmsPlan.vue
git commit -m "feat(frontend): 充放电计划页列表+Tab 分栏重构（名称化/筛选）"
```

### Task 3: 后端 planPage 增加 status 筛选

**Files:**
- Modify: `backend/energy-ems/src/main/java/com/energyx/ems/service/EmsPlanService.java`（`page` 方法）
- Modify: `backend/energy-ems/src/main/java/com/energyx/ems/web/EmsPlanController.java`（`page` 接口加 status 参数）

**Interfaces:**
- Consumes: 现有 `EmsPlanMapper.selectPage`
- Produces: `page(long pageNo, long pageSize, Long stationId, Integer status)`；`GET /ems/plan/page?status=` 可选

- [ ] **Step 1: 改 Service.page 加 status**

Modify: `EmsPlanService.java:334` 附近：
```java
public Page<EmsPlan> page(long pageNo, long pageSize, Long stationId, Integer status) {
    return planMapper.selectPage(new Page<>(pageNo, pageSize),
            new LambdaQueryWrapper<EmsPlan>()
                .eq(stationId != null, EmsPlan::getStationId, stationId)
                .eq(status != null, EmsPlan::getStatus, status)
                .orderByDesc(EmsPlan::getPlanDate));
}
```

- [ ] **Step 2: 改 Controller 加 status 参数**

Modify: `EmsPlanController.java`：
```java
@GetMapping("/page")
public Result<PageResult<EmsPlan>> page(@RequestParam(defaultValue = "1") long pageNo,
        @RequestParam(defaultValue = "10") long pageSize, @RequestParam(required = false) Long stationId,
        @RequestParam(required = false) Integer status) {
    return Result.ok(PageResult.of(service.page(pageNo, pageSize, stationId, status)));
}
```

- [ ] **Step 3: 格式化 + 测试**

Run: `mvn.cmd -o spring-javaformat:apply -pl energy-ems '-Dmaven.repo.local=D:\Program Files\maven-repo'` 后 `mvn.cmd -o test -pl energy-ems ...`
Expected: BUILD SUCCESS，9 个测试全绿

- [ ] **Step 4: 提交**

```bash
git add backend/energy-ems/src/main/java/com/energyx/ems/service/EmsPlanService.java backend/energy-ems/src/main/java/com/energyx/ems/web/EmsPlanController.java
git commit -m "feat(ems): planPage 增加 status 筛选"
```

### Task 4: 前端 ems.ts 同步 planPage 参数 + 重建/冒烟

**Files:**
- Modify: `frontend/src/api/ems.ts`（`planPage` 透传 status）

**Interfaces:**
- Consumes: Task 3 后端接口
- Produces: `emsApi.planPage({pageNo,pageSize,stationId,status})` 可传 status

- [ ] **Step 1: ems.ts planPage 参数补 status**

Modify: `frontend/src/api/ems.ts`：
```ts
/** GET /api/ems/plan/page 计划分页（stationId/status 可选过滤） */
planPage(params: Record<string, unknown>): Promise<PageResult<EmsPlan>> { return http.get('/api/ems/plan/page', { params }) },
```
（已是 `Record<string, unknown>` 透传，无需改；确认即可）

- [ ] **Step 2: vue-tsc + 前端构建**

Run: `cd frontend && ./node_modules/.bin/vue-tsc --noEmit && npm run build`
Expected: PASS

- [ ] **Step 3: 提交（若 ems.ts 有实际改动）**

```bash
git add frontend/src/api/ems.ts
git commit -m "feat(frontend): planPage 支持状态筛选参数"
```
（无改动则跳过提交）

### Task 5: 波形叠加实际功率曲线

**Files:**
- Modify: `frontend/src/views/EmsPlan.vue`（renderChart 增加实际曲线 series）

**Interfaces:**
- Consumes: Task 1 `fetchActualCurve(deviceId, productKey, planDate)`；`deviceApi.page` 反查 deviceId；`selected.planDate`
- Produces: 波形叠加"实际功率"虚线（浅色 dash），tooltip 显示计划/实际对比；失败降级隐藏

- [ ] **Step 1: 选择计划时反查 deviceId 并拉实际曲线**

Modify: `EmsPlan.vue` script，`selectPlan` 内追加：
```ts
// 反查下发设备（energyx.ems.device-name 对应设备），失败则跳过实际曲线
async function loadActualCurve(plan: EmsPlan): Promise<void> {
  actualCurve.value = null
  try {
    const page = await deviceApi.page({ pageNum: 1, pageSize: 20 })
    const dev = page.records.find((d) => d.deviceName === PCS_DEVICE_NAME)
    if (!dev) return
    actualCurve.value = await fetchActualCurve(String(dev.deviceId), dev.productKey, plan.planDate.slice(0, 10))
  } catch {
    actualCurve.value = null // 降级：无实际曲线不阻断波形
  }
}
```
（`PCS_DEVICE_NAME` 常量与后端 `energyx.ems.device-name` 对齐，缺省 `ess-dev-01`；`deviceName/productKey` 字段名按 `Device` 类型确认）

- [ ] **Step 2: renderChart 增加实际曲线 series**

Modify: `renderChart`，在 `SOC` series 之后追加：
```ts
...(actualCurve.value && actualCurve.value.power.length
  ? [{
      name: '实际功率',
      type: 'line',
      yAxisIndex: 0,
      data: actualCurve.value.power,
      smooth: true,
      symbol: 'none',
      lineStyle: { color: '#2B6CB0', width: 1.2, type: 'dashed' },
    }]
  : []),
```
（数据长度与 `times` 对齐；若实际曲线时间戳与计划 5min 槽不完全对齐，用 ECharts xAxis `data` 以计划 times 为轴、实际按近似索引——实现时按实际 times 重新对齐或直接共享索引，标注取舍）

- [ ] **Step 3: 空态提示**

Modify: 图例区，`actualCurve.value` 为空时显示灰置"实际（无数据）"

- [ ] **Step 4: vue-tsc + 提交**

```bash
cd frontend && ./node_modules/.bin/vue-tsc --noEmit
git add frontend/src/views/EmsPlan.vue
git commit -m "feat(frontend): 波形叠加实际功率曲线（TSDB 数据源，失败降级）"
```

---

## Part B：分布式调度 xxl-job 迁移

### Task 6: 基建——docker-compose 加 xxl-job-admin + 初始化 SQL

**Files:**
- Modify: `deploy/docker/docker-compose.yml`（新增 xxl-job-admin 服务）
- Create: `deploy/sql/xxl_job_init.sql`（官方 xxl-job 2.4.x schema，从 https://github.com/xuxueli/xxl-job 官方 SQL 复制）
- Create: `deploy/scripts/init-xxl-job.sh`（幂等初始化：建库 + 导 schema + 建 7 个任务行）

**Interfaces:**
- Produces: `http://127.0.0.1:8099/xxl-job-admin`（admin 控制台，默认 admin/123456）；`xxl_job` 库

- [ ] **Step 1: docker-compose 增加 xxl-job-admin**

Modify: `deploy/docker/docker-compose.yml`：
```yaml
  xxl-job-admin:
    image: xuxueli/xxl-job-admin:2.4.1
    container_name: ems-xxl-job-admin
    environment:
      PARAMS: "--spring.datasource.url=jdbc:mysql://host.docker.internal:3306/xxl_job?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai --spring.datasource.username=root --spring.datasource.password=root&QAQ"
    ports:
      - "8099:8080"
    depends_on:
      - mysql
    restart: unless-stopped
```
（若 compose 无 mysql 服务——本机 MySQL——改用 `host.docker.internal`；镜像 2.4.1 与 xxl-job-core 2.4.x 对齐）

- [ ] **Step 2: 下载官方 schema 并落库**

Run: 从 xxl-job 官方仓库取 `tables_xxl_job.sql`，经 mysql2/MySQL 客户端导入本机 `xxl_job` 库
Expected: `xxl_job.xxl_job_info` 等表存在

- [ ] **Step 3: 起容器并验证 admin**

Run: `docker compose up -d xxl-job-admin`；`curl http://127.0.0.1:8099/xxl-job-admin`
Expected: HTTP 200（登录页）

- [ ] **Step 4: 提交**

```bash
git add deploy/docker/docker-compose.yml deploy/sql/xxl_job_init.sql deploy/scripts/init-xxl-job.sh
git commit -m "feat(deploy): xxl-job-admin 容器 + 初始化 SQL"
```

### Task 7: parent pom + 7 模块依赖与执行器配置

**Files:**
- Modify: `backend/pom.xml`（`<properties>` 加 `xxl-job.version=2.4.1`，dependencyManagement 加 xxl-job-core）
- Modify: `backend/energy-{ems,command,device,shadow,system,alarm}/pom.xml`（加 xxl-job-core 依赖）
- Create: 6 个模块 `config/XxlJobConfig.java`（`XxlJobSpringExecutor` Bean + `@ConfigurationProperties`）
- Modify: 6 个模块 `application.yml`（`xxl.job.*` 配置，executor.port 9990~9995）

**Interfaces:**
- Consumes: xxl-job-core 2.4.1
- Produces: 各服务启动时自动注册到 admin（appname=energy-xxx）

- [ ] **Step 1: parent pom 加版本管理**

Modify: `backend/pom.xml`：
```xml
<properties>
  <xxl-job.version>2.4.1</xxl-job.version>
</properties>
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>com.xuxueli</groupId>
      <artifactId>xxl-job-core</artifactId>
      <version>${xxl-job.version}</version>
    </dependency>
  </dependencies>
</dependencyManagement>
```

- [ ] **Step 2: 6 模块 pom 加依赖**

每个模块 pom（ems/command/device/shadow/system/alarm）：
```xml
<dependency>
  <groupId>com.xuxueli</groupId>
  <artifactId>xxl-job-core</artifactId>
</dependency>
```

- [ ] **Step 3: 每模块建 XxlJobConfig**

Create（以 ems 为例，其余 5 个同构改包名/appname/port）：
`backend/energy-ems/src/main/java/com/energyx/ems/config/XxlJobConfig.java`：
```java
package com.energyx.ems.config;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** xxl-job 执行器装配（低频 cron 任务迁移调度中心） */
@Configuration
public class XxlJobConfig {

    @Bean
    @ConfigurationProperties(prefix = "xxl.job.executor")
    public XxlJobSpringExecutor xxlJobExecutor() {
        return new XxlJobSpringExecutor();
    }

}
```

- [ ] **Step 4: 6 模块 application.yml 加配置**

Modify: 各模块 `application.yml` 追加（ems 用 9990，command 9991，device 9992，shadow 9993，system 9994，alarm 9995）：
```yaml
xxl:
  job:
    admin:
      addresses: http://127.0.0.1:8099/xxl-job-admin
    accessToken: energyx-xxl-job-token
    executor:
      appname: energy-ems
      address: ''
      ip: ''
      port: 9990
      logpath: ./logs/xxl-job/
      logretentiondays: 30
```

- [ ] **Step 5: 构建验证（先拉依赖）**

Run: `mvn.cmd -o -pl energy-common,energy-ems,energy-command,energy-device,energy-shadow,energy-system,energy-alarm -am install -DskipTests`（首次需联网拉 xxl-job-core）
Expected: BUILD SUCCESS

- [ ] **Step 6: 提交**

```bash
git add backend/pom.xml backend/energy-*/pom.xml backend/energy-*/src/main/java/com/energyx/*/config/XxlJobConfig.java backend/energy-*/src/main/resources/application.yml
git commit -m "feat(ems): xxl-job 执行器接入（6 模块依赖+配置）"
```

### Task 8: 7 处 @Scheduled 改 @XxlJob（低频任务迁移）

**Files:**
- Modify: `backend/energy-ems/.../service/EmsPlanService.java`（`generateDailyPlans`）
- Modify: 6 个 `*RetentionCleaner.java`（`scheduledClean`）——ems/command/device/shadow/system/alarm

**Interfaces:**
- Consumes: Task 7 执行器
- Produces: 7 个 `@XxlJob("xxx")` 方法，admin 控制台可见并触发

- [ ] **Step 1: 每日计划生成改 @XxlJob**

Modify: `EmsPlanService.java:144`：
```java
/** 每日 00:05 生成次日计划（xxl-job 触发，admin cron=0 5 0 * * *） */
@XxlJob("emsDailyPlanGenerate")
public void generateDailyPlans() {
    distributedLock.runIfAcquired("scheduled:ems-daily-plan", 600, this::doGenerateDailyPlans);
}
```
（import `com.xxl.job.core.handler.annotation.XxlJob`；去掉 `@Scheduled`；方法保持 public）

- [ ] **Step 2: 6 个 Cleaner 改 @XxlJob**

以 `ExecutionRecordRetentionCleaner.java` 为例（其余 5 个同构）：
```java
/** 每日 03:30 清理保留期外数据（xxl-job 触发，admin cron=0 30 3 * * *） */
@XxlJob("emsExecutionRetentionClean")
public void scheduledClean() {
    distributedLock.runIfAcquired(LOCK_KEY, 600, () -> { ... 原逻辑不变 ... });
}
```
各任务名：`emsExecutionRetentionClean` / `commandRetentionClean` / `deviceOnlineRetentionClean` / `shadowHistoryRetentionClean` / `systemOperatorLogRetentionClean` / `alarmRetentionClean`
（import 去掉 `@Scheduled`；`@Component` 保留）

- [ ] **Step 3: admin 注册 7 个任务**

Create: `deploy/scripts/init-xxl-job.sh` 追加（或控制台手建），每任务：job_desc / job_group(执行器) / schedule_conf(cron) / executor_handler(@XxlJob 名) / glue_type=BEAN / executor_block_strategy=SERIAL_EXECUTION / executor_fail_strategy=FAIL_ALARM
7 个任务：emsDailyPlanGenerate(0 5 0 * * *) + 6 个 RetentionClean(0 30 3 * * *)

- [ ] **Step 4: 格式化 + 构建 + 测试**

Run: 对 6 个模块 `mvn.cmd -o spring-javaformat:apply` + `mvn.cmd -o test -pl energy-ems`（ems 9 测试全绿）
Expected: BUILD SUCCESS

- [ ] **Step 5: 提交**

```bash
git add backend/energy-*/src/main/java deploy/scripts/init-xxl-job.sh
git commit -m "feat(ems): 7 个低频 cron 任务迁移 xxl-job（@XxlJob 注解）"
```

### Task 9: 高频任务保留确认 + 全链路验证

**Files:**
- 无代码改动（仅验证）

**Interfaces:**
- Consumes: Task 6-8 全部产物

- [ ] **Step 1: 确认 4 个高频任务未被动过**

Run: Grep `TsdbFlushScheduler|CommandTimeoutScanner|AlarmService|PlanExecutionScheduler` 仍为 `@Scheduled`
Expected: 4 处仍是 @Scheduled + DistributedLock（未被迁移）

- [ ] **Step 2: 启动验证**

Run: 起 xxl-job-admin + 6 个服务；admin 控制台执行器列表可见 6 个 appname
Expected: 执行器在线；7 个任务可手动触发，日志落 admin

- [ ] **Step 3: 高频任务回归**

Run: 观察 energy-tsdb 1s flush、energy-command 5s 扫描、energy-ems 1min 计划调度日志
Expected: 正常输出，不受迁移影响

- [ ] **Step 4: 提交（如有验证修正）**

```bash
git add -A
git commit -m "chore(ems): xxl-job 迁移后全链路验证"  # 无改动则跳过
```

---

## Self-Review 记录

- **Spec 覆盖**：Part A 覆盖 spec §2 全部（布局/名称化/筛选/实际曲线/空态）；Part B 覆盖 §3 全部（admin/依赖/执行器/7 任务迁移/高频保留）✅
- **占位符扫描**：全部步骤含具体代码/命令；`init-xxl-job.sh` 为脚本创建任务，步骤 3 给了字段清单 ✅
- **类型一致性**：`fetchActualCurve` 返回 `{times, power}` 与 renderChart series 对齐；`page()` 签名在 Task 3 统一为 4 参 ✅
