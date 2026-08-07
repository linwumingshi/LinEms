# energy-ems 储能策略引擎 + 前端策略/计划页面 设计

日期：2026-08-07
状态：已批准（完整版：多策略 + 下发链路）
关联：`sql/mysql/70_ems.sql`（5 表已设计）、`docs/design/Phase1-整体架构设计.md`（§2.4 控制分级、§7.1 ems-plan topic）、`backend/energy-command`（复用下发链路）、`backend/energy-common`（条件化租户拦截器）

## 1. 目标

落地 **energy-ems 储能策略引擎服务**（Phase1 规划、当前无代码）+ **前端策略管理/计划页面**。完整版范围：多策略类型（峰谷套利/需量/需求响应/SOC 约束/时间策略）+ 优先级仲裁 + 充放电计划生成（点序列入 TDengine）+ 安全包络校验 + 计划下发接通 energy-command 指令链路。

## 2. 用户场景

1. 运营在「策略管理」页为某电站创建峰谷套利策略（配置谷充峰放窗口 + 功率 + SOC 范围），启用
2. 页面点「生成计划」或每日定时，EMS 读取分时电价 + 安全约束，生成次日 24h 充放电点序列
3. 计划详情页用 ECharts 展示点序图；每个点经安全包络校验后调 command 服务下发指令
4. 执行记录表可追溯每次下发的 commandId 与回执

## 3. 约束

- Java 17 / Spring Boot 3.x，对齐 energy-product/device/station 现有模块结构（entity/mapper/service/web/util + @MapperScan）
- 5 张业务表已由 `sql/mysql/70_ems.sql` 设计（策略/计划/电价/安全约束/执行记录），直接复用，不改表结构
- 所有表带 `tenant_id`，复用 `energy-common` 条件化租户拦截器（创建时 `requireTenant()`，查询自动追加条件）
- 点序列存 TDengine：新建 `ems_plan_point` STABLE（按 stationId 建子表），MySQL 只存计划头（70_ems.sql §2 注释约定）
- 下发**复用** energy-command 的 `POST /api/command`（现成幂等/QoS1/ACK 状态机），EMS 不重复造下发；执行记录记 commandId
- 安全包络校验在 EMS 侧：生成点序列前校验 SOC/功率/温度（Phase1 §2.4「云端只做优化级控制，下发值必须经安全包络校验」）
- 计划生成触发：页面手动 + `@Scheduled(cron="0 5 0 * * *")` 每日 00:05 为启用策略生成次日计划
- 本期不实现 AI 预测算法（SOC/SOH/负荷/电价预测留接口占位，由后续 AI 服务接）

## 4. 架构与组件

```
[前端 策略管理页] → Gateway /api/ems → energy-ems (8105)
                                        │
                    ┌───────────────────┼────────────────────┐
                    │                   │                    │
              策略/电价/约束 CRUD    计划生成             安全包络校验
              (5 表 MySQL)      (PlanGenerator)      (SafetyEnvelopeValidator)
                    │                   │                    │
                    │              ems_plan_point      POST /api/command
                    │               (TDengine STABLE)  → energy-command
                    │                   │                (复用指令链路)
                    │              ems-plan topic (Kafka)
                    │                                   │
              执行记录 (ems_execution_record)   → 设备下发
```

### 后端模块 `backend/energy-ems/`

```
├── EnergyEmsApplication.java          # @SpringBootApplication + @MapperScan("com.energyx.ems.mapper")
├── entity/    EmsStrategy / EmsPlan / EmsElectricityPrice / EmsConstraint / EmsExecutionRecord
├── mapper/    对应 5 表 Mapper（extends BaseMapper<T>）
├── service/
│   ├── EmsStrategyService.java        # 策略 CRUD + 启用/停用
│   ├── EmsPriceService.java           # 分时电价 CRUD
│   ├── EmsConstraintService.java      # 安全约束管理（一电站一条，唯一键 uk_constraint_station）
│   ├── EmsPlanService.java            # 计划生成/查询 + 点序列 TDengine + 下发（复用 command）
│   └── SafetyEnvelopeValidator.java   # 安全包络校验（纯逻辑，可单测）
├── web/
│   ├── EmsStrategyController.java     # /api/ems/strategy
│   ├── EmsPriceController.java        # /api/ems/price
│   ├── EmsConstraintController.java   # /api/ems/constraint
│   └── EmsPlanController.java         # /api/ems/plan
└── util/
    └── PlanGenerator.java             # 峰谷套利策略→24h 点序列（纯函数，可单测）
```

### 组件边界（单一职责，可独立测试）

- **PlanGenerator**：纯函数。输入 `(EmsStrategy.config, 分时电价列表, EmsConstraint, socInit)` → 输出 24h 点序列 `[{time:LocalTime, action:CHARGE|DISCHARGE|STANDBY, powerKw, socTarget}]`。峰谷套利：谷段充电、峰段放电；受 SOC 范围与功率上限裁剪。不碰 DB、不碰 MQTT/Kafka。
- **SafetyEnvelopeValidator**：纯函数。校验点序列每行 `soc_min ≤ soc ≤ soc_max`、`power ≤ charge/discharge_power_max`、温度不超限。任一超限 → 返回拒绝/裁剪原因。单测覆盖边界。
- **EmsPlanService**：编排层。调 PlanGenerator 生成 → 校验器过包络 → 点序列写 TDengine → 逐点调 command 建指令 → 写执行记录。依赖注入 mapper + TdengineTemplate + CommandClient。
- **CommandClient**：封装调用 energy-command 的 `POST /api/command`（OpenFeign 直连 `http://energy-command:8114`，服务间内网不走网关）。薄封装，可替换。

## 5. 数据流

1. **计划生成（手动）**：`POST /api/ems/plan/generate {stationId, strategyId?, planDate}` →
   - 查启用策略（未指定则取该电站优先级最高的启用策略）+ 分时电价 + 安全约束 + SOC 初值（查影子或默认 50%）
   - PlanGenerator 出点序列 → SafetyEnvelopeValidator 校验
   - 点序列写 TDengine `ems_plan_point`（按 stationId 建子表）
   - 计划头写 MySQL `ems_plan`（status=0 待执行）
   - 返回计划头 + 点序列摘要
2. **下发**：`POST /api/ems/plan/{planId}/dispatch` →
   - 对计划每个点调 `POST /api/command` 建指令（复用链路，自动走 QoS1/ACK）
   - 每点写 `ems_execution_record`（planId/commandId/action/params）
   - 计划头 status=1 执行中
3. **每日定时**：`@Scheduled` 00:05 → 为所有启用策略的电站生成次日计划（复用步骤 1 内部逻辑）
4. **Kafka**：计划生成事件发 `ems-plan` topic（`{stationId}` key，Phase1 §7.1 已规划），供 report/审计消费；本期只发不消

## 6. API 一览

| 方法 | 路径 | 说明 |
|---|---|---|
| GET/POST | `/api/ems/strategy` | 策略分页/创建 |
| PUT/DELETE | `/api/ems/strategy/{id}` | 更新/删除；PUT 支持 status 切换启用/停用 |
| GET/POST | `/api/ems/price` | 分时电价分页/批量配置 |
| PUT | `/api/ems/price/{id}` | 电价更新 |
| GET/PUT | `/api/ems/constraint` | 安全约束查询/保存（一电站一条 upsert） |
| POST | `/api/ems/plan/generate` | 生成计划（手动） |
| POST | `/api/ems/plan/{planId}/dispatch` | 下发计划 |
| GET | `/api/ems/plan/page` | 计划分页 |
| GET | `/api/ems/plan/{planId}/points` | 计划点序列（TDengine 查询） |

> 网关路由 `/api/ems/**` StripPrefix=1（对齐 product/device/station 资产模块），控制器映射不带 `/api`。端口 8105（README 端口表未占）。

## 7. 前端页面

新增 2 个页面 + 侧边栏入口，对齐 Command.vue/Alarm.vue 现有模式（request.ts + Element Plus 表格/弹窗/Tag）。

### 7.1 策略管理 `frontend/src/views/EmsStrategy.vue`（路由 `/ems/strategy`）
- 策略列表表格：名称/类型/优先级/状态/作用电站/更新时间
- 新增/编辑弹窗：策略类型下拉（PEAK_VALLEY/DEMAND/DR/SOC_CTRL/TIME）+ 配置表单（峰谷窗口时间、功率上限、SOC 范围 JSON 编辑）
- 启用/停用 Tag 切换
- 「生成计划」按钮（选中电站 → `POST /api/ems/plan/generate`）

### 7.2 计划与执行 `frontend/src/views/EmsPlan.vue`（路由 `/ems/plan`）
- 计划列表：日期/电站/策略/类型/状态/总量
- 计划详情：24h 点序图（ECharts，时间 vs 功率柱状，充/放/待机着色）
- 执行记录表：action/commandId/result/执行时间

### 7.3 侧边栏
MainLayout 菜单加「策略管理」分组（EMS 子菜单：策略/计划）。

## 8. 测试策略

**单测（重点，TDD）：**
- `PlanGeneratorTest`：峰谷套利基础场景、SOC 边界裁剪、功率上限裁剪、空电价/无策略异常
- `SafetyEnvelopeValidatorTest`：SOC 越界拒绝、功率越界拒绝、温度越界拒绝、边界值通过
- Service 层用 Mockito 测编排（生成→校验→落库→下发调用链）

**冒烟（验证链路）：**
- 建策略 → 配电价/约束 → 生成计划 → 查点序列 → 下发 → 查执行记录 + command 服务侧指令出现

## 9. 验收口径

- 策略/电价/约束 CRUD 经网关可操作，多租户隔离生效（租户 A 看不到租户 B 数据）
- 峰谷套利策略生成的点序列符合谷充峰放 + SOC/功率约束
- 超限点被安全包络拒绝或裁剪，有明确原因
- 下发后 command 服务出现对应指令（commandId 回填执行记录）
- 每日 00:05 定时为启用策略自动生成次日计划
- 前端策略/计划页面可操作、点序图展示正确
- 全量 mvn test 通过
