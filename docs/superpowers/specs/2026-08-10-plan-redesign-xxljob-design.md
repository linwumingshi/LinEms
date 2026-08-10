# EnergyX 充放电计划页重设计 + 分布式调度（xxl-job）迁移设计

> 日期：2026-08-10 ｜ 状态：待审阅
> 范围：① `EmsPlan.vue` 页面整体重设计（列表 + Tab 分栏 + 实际曲线叠加）；② 后端低频 cron 定时任务迁移 xxl-job
> 依据：用户需求确认（列表+Tab 分栏布局；秒级任务留本地 @Scheduled，cron 任务迁 xxl-job）；代码盘点（12 处 @Scheduled）

---

## 一、背景与目标

### 1.1 充放电计划页面现状痛点（读码结论）

`frontend/src/views/EmsPlan.vue` 现状：页头（副题裸 ID）→ 读数带（5 项）→ 波形卡（ECharts 计划曲线 + 电价底纹）→ 执行记录表（P0 闭环后新增）→ 计划列表（分页）。痛点：

| # | 痛点 | 证据 |
|---|---|---|
| P1 | 列表/副题显示裸 `stationId`/`strategyId`，无法分辨电站与策略 | `EmsPlan.vue:319` 副题；`:377-382` 列表列 |
| P2 | 无筛选（电站/状态/日期），计划多时不可用 | 列表仅分页，无筛选条件 |
| P3 | 波形只显示计划目标，无实际功率曲线对比 | `renderChart` 仅渲染 pts + 电价底纹 |
| P4 | 页面纵向堆叠过长（波形 + 执行记录 + 列表），重点不突出 | 卡片顺序：readout → wave → exec → list |
| P5 | 状态展示弱（仅 Tag），无进度/失败原因引导 | `STATUS_TEXT` 4 态 |

### 1.2 定时任务现状（后端盘点）

全项目 **12 处 @Scheduled**，全部已挂 R-01 分布式锁（多实例互斥正确性已达标）：

| 类别 | 任务 | 频率 | 模块 |
|---|---|---|---|
| 低频 cron（7） | 每日生成次日计划 `generateDailyPlans` | 0 5 0 * * * | ems |
|  | 6 个数据清理 `*RetentionCleaner`（执行记录/指令/在线记录/影子历史/操作日志/告警） | 0 30 3 * * * | ems/command/device/shadow/system/alarm |
| 高频内部循环（4） | TDengine 空闲冲刷 `TsdbFlushScheduler` | 1s | tsdb |
|  | 指令 ACK 超时扫描 `CommandTimeoutScanner` | 5s | command |
|  | 告警规则缓存刷新 `AlarmService` | 30s | alarm |
|  | 计划到点执行 `PlanExecutionScheduler` | 1min | ems |

**决策**：xxl-job 是中心化 cron 调度，适合"低频业务任务"（可观测/告警/重试）；**不适合秒级内部状态机**（网络往返延迟、中心压力、无 fixedDelay 语义）。故仅迁移 7 个 cron 任务，4 个高频循环保留本地 @Scheduled + 现有锁。

---

## 二、Part A：充放电计划页重设计

### 2.1 布局（列表 + Tab 分栏）

```
┌──────────────────────────────────────────────────────────────┐
│ 页头：充放电计划  副题:电站名·策略名·计划日期                    │
│      [生成计划] [电站筛选▾] [状态筛选▾]                        │
├──────────────────┬───────────────────────────────────────────┤
│ 计划列表          │  Tab1 计划波形  │  Tab2 执行记录            │
│ ┌──────────────┐ │  ┌───────────┐ │  ┌────────────────────┐  │
│ │ 日期 电站 策略 │ │  │ 功率柱状   │ │  │ 时刻 动作 状态 指令ID │  │
│ │ 总量 状态 操作 │ │  │ (充绿/放琥珀│ │  │ 回执               │  │
│ │              │ │  │ /待机灰)   │ │  └────────────────────┘  │
│ │              │ │  │ SOC 目标线 │ │                         │
│ │ 分页          │ │  │ 电价底纹   │ │                         │
│ └──────────────┘ │  │ 实际曲线叠加│ │                         │
│                  │  └───────────┘ │                         │
└──────────────────┴────────────────┴─────────────────────────┘
```

### 2.2 组件与数据流

| 区域 | 内容 | 数据源 |
|---|---|---|
| 列表（左，宽 ~360px） | 日期/电站名/策略名/总量/状态 Tag/操作（查看/下发）；电站下拉 + 状态下拉筛选 | `planPage({stationId,status})`；名称经 `loadStations()`/`strategyPage` 映射 |
| Tab1 波形（右主区） | 功率柱状 + SOC 线 + 电价底纹 + **实际功率虚线** | `planPoints` + `pricePage` + `tsdb.propertyHistory(power,soc)` |
| Tab2 执行记录 | 计划时刻/动作/点状态/指令 ID/回执（复用现表） | `planRecords` |
| 页头副题 | 电站名 · 策略名 · 计划日期 | 名称映射 + `selected` |

### 2.3 实际曲线叠加（新能力）

- **数据源**：`GET /api/tsdb/property/history?deviceId=&productKey=&identifiers=power,soc&startTime=&endTime=&order=asc&page=1&size=2000`
- **参数映射**：`deviceId`/`productKey` 从下发配置取（`energyx.ems.product-key`、`energyx.ems.device-name`，前端可经 `deviceApi` 反查 deviceId）；时间范围 = 计划日 00:00–24:00
- **渲染**：ECharts 第二条线（虚线、浅色）叠加在功率柱状图上；tooltip 显示"计划 X kW / 实际 Y kW"
- **降级**：拉取失败静默，仅隐藏实际曲线（不影响计划波形），不报错
- **边界**：计划日未到/无上报数据 → 实际曲线为空，图例置灰

### 2.4 交互与状态

- 行点击 → 选中计划 → Tab1 渲染波形 + Tab2 加载执行记录（联动）
- 下发 → 受理 toast（含立即下发点数）→ 刷新列表 + Tab2
- 状态色：待执行灰 / 执行中蓝 / 完成绿 / 失败红 / 已取消灰
- 空态：无执行记录 → "下发后调度器到点执行"；实际曲线无数据 → 图例"实际（无数据）"

### 2.5 测试

- 前端 `vue-tsc --noEmit` + 构建；列表筛选/名称化/Tab 切换冒烟
- 实际曲线叠加：mock tsdb 返回，验证渲染与降级

---

## 三、Part B：分布式调度 xxl-job 迁移

### 3.1 架构

```
┌──────────────┐  注册/心跳   ┌─────────────────────────┐
│ xxl-job-admin │ ◄────────── │ energy-ems / command /   │
│ (Docker + DB) │  调度触发   │ shadow / system / alarm  │
└──────┬───────┘            └─────────────────────────┘
       │ cron 触发
       ▼
  执行器 Bean（@XxlJob）→ 原任务逻辑（去掉 @Scheduled + 分布式锁）
```

### 3.2 迁移清单

| 任务 | 模块 | cron | 处理 |
|---|---|---|---|
| 每日生成次日计划 | ems | 0 5 0 * * * | 迁 xxl-job |
| 执行记录清理 | ems | 0 30 3 * * * | 迁 xxl-job |
| 指令数据清理 | command | 0 30 3 * * * | 迁 xxl-job |
| 在线记录清理 | device | 0 30 3 * * * | 迁 xxl-job |
| 影子历史清理 | shadow | 0 30 3 * * * | 迁 xxl-job |
| 操作日志清理 | system | 0 30 3 * * * | 迁 xxl-job |
| 告警数据清理 | alarm | 0 30 3 * * * | 迁 xxl-job |
| TSDB 冲刷 | tsdb | 1s | **留本地** |
| 指令超时扫描 | command | 5s | **留本地** |
| 告警规则刷新 | alarm | 30s | **留本地** |
| 计划到点执行 | ems | 1min | **留本地** |

> 说明：6 个 Cleaner 共用 `DataRetention` 工具 + 各自 `distributedLock`——迁 xxl-job 后由调度中心保证单实例触发，**锁仍保留**（防 admin 双活/重试重叠）。

### 3.3 依赖与配置

- **新增依赖**：7 个模块 pom 加 `xxl-job-core`（parent 统一版本管理）
- **xxl-job-admin**：docker-compose 新增容器（`xuxueli/xxl-job-admin` + 内置 H2 或挂 MySQL `xxl_job` 库）；端口 8099；首次初始化 `xxl_job` schema（官方 SQL）
- **执行器配置**（各服务 application.yml）：
  ```yaml
  xxl:
    job:
      admin:
        addresses: http://127.0.0.1:8099/xxl-job-admin
      accessToken: <共享令牌>
      executor:
        appname: energy-ems   # 与 admin 执行器名一致
        address: ''           # 自动注册
        ip: ''                # 自动探测
        port: 9990            # 各服务递增（9990~9995，共 6 个执行器：ems/command/device/shadow/system/alarm）
        logpath: ./logs/xxl-job/
        logretentiondays: 30
  ```
- **执行器装配**：各服务新增 `XxlJobConfig`（`XxlJobSpringExecutor` Bean，`@ConfigurationProperties` 绑定）
- **任务改造**：7 处 `@Scheduled` 方法改 `@XxlJob("taskName")` 注解（去掉 cron 与 @Scheduled）；`XxlJobHelper.log` 记录进度
- **admin 任务注册**：admin 控制台手动建 7 个任务（cron 同上），执行器选对应 appname；或提供初始化 SQL（推荐后者，可重复执行）

### 3.4 移除/保留

- 移除：7 处 `@Scheduled` + 各自 cron；`@EnableScheduling` 保留（高频任务仍需）
- 保留：`DistributedLock`（R-01）在 4 个高频任务 + 7 个迁移任务的逻辑内仍加锁（双保险）

### 3.5 测试

- 本地起 xxl-job-admin（docker compose）+ 各服务注册成功（admin 执行器列表可见）
- 7 个任务手动触发一次，验证逻辑执行 + 日志落 admin
- 高频任务回归：1s 冲刷、5s 扫描、计划到点执行不受影响
- 全量单测 + 格式化 + 构建

---

## 四、实施顺序（建议）

1. **Part A 页面重设计**（前端，独立可交付）
2. **Part B-1 基建**：docker-compose 加 xxl-job-admin + 初始化 SQL + 7 模块依赖/配置/执行器
3. **Part B-2 任务迁移**：7 处注解改造 + admin 任务注册
4. 验证 + 提交

## 五、风险与权衡

| 项 | 说明 |
|---|---|
| xxl-job-admin 单点 | admin 需高可用时多实例 + DB，本期单实例可接受（调度中心宕机仅影响 cron 任务，高频执行不受影响） |
| 秒级任务不迁移 | 已确认：中心化调度不适合 1s/5s 内部循环，留本地正确 |
| 实际曲线数据完整性 | 依赖模拟设备持续上报；生产接真实 PCS 数据即可 |
| 迁移期双跑 | 改造期间新旧任务可能同时触发——先停旧 @Scheduled 再建 admin 任务（同 cron 窗口） |
