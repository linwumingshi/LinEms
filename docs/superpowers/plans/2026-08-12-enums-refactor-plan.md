# 实施计划：业务状态/分类值全量枚举化（前后端）

计划日期：2026-08-12
设计文档：docs/superpowers/specs/2026-08-12-enums-refactor-design.md
执行方式：Subagent-Driven（用户已确认后续均用推荐方式）

## 任务总览

| # | 任务 | 模块 | 枚举 | 关键产出 |
|---|---|---|---|---|
| 1 | common 枚举基座 | energy-common/device/command | DeviceStatus, CommandState | 建 `com.energyx.common.enums` 包 + 枚举模式 + device/command 实体字段改造 + 测试 |
| 2 | ems 模块枚举 | energy-ems | StrategyStatus, PlanStatus, PlanPointState, ElectricityPriceStatus, ConstraintStatus, PriceType, StrategyType, RevenuePeriodType | ems 全枚举 + 调用方 + 测试 |
| 3 | 其余模块枚举 | alarm/product/station/system/access | AlarmLevel, AlarmRecordStatus, AlarmRuleStatus, ProductStatus, ThingModelStatus, StationStatus, GridType, UserStatus, RoleStatus, PermissionStatus, TenantStatus, DataScope, EnterpriseLevel, CredentialAuthStatus, DeviceType, EventSeverity | 其余全枚举 + 调用方 + 测试 |
| 4 | 前端枚举化 | frontend | 全部（TS as const） | enums.ts + dicts.ts 收敛 + models.ts 类型化 + 页面字面量替换 + vue-tsc |
| 5 | 全量回归 | 全栈 | - | 全模块 mvn test + vue-tsc + 冒烟 |

## 依赖

- Task 1 是基座（模式确立），Task 2/3 依赖其模式（可并行，但 subagent 逐个派发更稳）。
- Task 4 依赖 Task 1-3 的后端 code 语义（枚举值需与后端一致）。
- Task 5 是收口。

## Task 1：common 枚举基座（先做，建立模式）

**产出枚举**：`DeviceStatus`（0-5）、`CommandState`（0-6）放 `energy-common/src/main/java/com/energyx/common/enums/`。

**模式要点**（从设计文档 §3.1）：
- `@EnumValue` 标注 code 字段（int/String 均可）
- `@JsonValue` 输出 code；`@JsonCreator` fromCode
- `of(Integer)` 安全查找返回 null；`getDesc()`
- 中文注释按项目规范；spring-javaformat

**实体改造**：
- `energy-device/entity/Device.java`：`Integer status` → `DeviceStatus status`（Javadoc 同步）
- `energy-command/model/CommandRow.java`：`Integer state` → `CommandState state`
- 全模块搜索 `DEVICE_STATUS_*`/`CMD_STATE_*` 常量引用 → 替换为枚举（Constants 保留 @Deprecated 过渡）

**配置**：各服务 application.yml 加 `mybatis-plus.type-enums-package: com.energyx.common.enums`（仅改 device/command 相关的 yml；实施时确认现状）

**测试**：
- 新增 `DeviceStatusTest`/`CommandStateTest`（fromCode/of/非法 code）
- 更新 DeviceServiceImplTest 等受影响测试（setStatus(2)→setStatus(DeviceStatus.OFFLINE) 类）
- 编译：energy-common install + device/command test 全绿

**验收**：device/command 模块无魔法值比较；JSON 输出仍是数字。

## Task 2：ems 模块枚举

**枚举**：StrategyStatus(0草稿1启用2停用)、PlanStatus(0待执行1执行中2完成3已取消)、
PlanPointState(0待下发1已下发2成功3失败4超时)、ElectricityPriceStatus(0停用1启用)、
ConstraintStatus(0停用1启用，实施时以 SQL 种子确认)、PriceType(DEEP/VALLEY/FLAT/PEAK/PEEK)、
StrategyType(PEAK_VALLEY/DEMAND/DR/SOC_CTRL/TIME)、RevenuePeriodType(DAY/WEEK/MONTH)

**实体改造**：EmsStrategy.status/strategyType、EmsPlan.status、EmsExecutionRecord.state、
EmsElectricityPrice.status/priceType、EmsConstraint.status、RevenueSummary.periodType（入参 String→枚举）

**调用方**：EmsPlanService/PlanGenerator/StrategyService/EmsPriceService/EmsRevenueService 中
`getStatus() == X`/`"PEAK_VALLEY".equals(...)` → 枚举比较

**测试**：ems 模块现有测试更新 + 新增枚举单测；`mvn test -pl energy-ems` 全绿

## Task 3：其余模块枚举

**枚举**：AlarmLevel(1-4)、AlarmRecordStatus(0-2)、AlarmRuleStatus(0/1)、ProductStatus(0/1)、
ThingModelStatus(0/1/2)、StationStatus(0/1)、GridType(中文 code)、UserStatus(0/1/2)、
RoleStatus(0/1)、PermissionStatus(0/1)、TenantStatus(0/1)、DataScope(1-4)、
EnterpriseLevel(1/2)、CredentialAuthStatus(1/2)、DeviceType(7 类)、EventSeverity(INFO/WARN/ERROR)

**实体改造**：alarm 3 实体、product 2 实体、station 1、system 5（SysUser/Role/Permission/Tenant/Enterprise）、
device 2（Device.deviceType、DeviceCredential.authStatus）、access 1（ThingModelEvent.type）

**测试**：各模块测试更新 + 枚举单测；`mvn test -pl energy-alarm,energy-product,energy-station,energy-system,energy-access` 全绿

## Task 4：前端枚举化

**新建** `frontend/src/utils/enums.ts`：
- 全部业务枚举 `as const` 对象 + 派生 type（DeviceStatus/CommandState/StrategyStatus/PlanStatus/
  AlarmLevel/AlarmRecordStatus/StationStatus/UserStatus/RoleStatus/ProductStatus/ThingModelStatus/
  DataScope/CredentialAuthStatus/DeviceType/StrategyType/PriceType/GridType/EventSeverity 等）
- 每个枚举配 `XX_TEXT`（文案）/`XX_TAG`（标签色，仅状态类需要）

**改造**：
- `utils/dicts.ts`：各 text/tag 函数内部改从 enums.ts Record 取值，函数签名保持 number 兼容
- `types/models.ts`：status/state/level/type 字段 → 枚举派生类型
- 页面字面量替换：Device.vue（status 判断）、Alarm.vue、EmsPlan.vue、Station.vue、Product.vue、
  SystemUser.vue、SystemRole.vue、Dashboard.vue 等 grep 出的 `=== N` 状态比较 → 枚举引用

**验收**：`vue-tsc --noEmit` 通过；`npm test`（vitest）通过（dicts/alarmFormat 测试更新）

## Task 5：全量回归

- 全模块 `mvn -o test`（按模块分组跑，避免超时）+ 前端 `vue-tsc --noEmit` + `npm test`
- 启动关键服务（device/ems/alarm 至少）冒烟：
  - 设备列表/详情接口 JSON 与改造前一致（status 仍是数字）
  - 策略启用/停用、计划状态流转正常
  - 告警级别标签正常
- 收尾：删除 Constants 中已废弃的 DEVICE_STATUS_*/CMD_STATE_*（若全部引用已迁移）

## 关键约束（每个 Task 通用）

1. 中文注释、无行尾注释、spring-javaformat（`mvn spring-javaformat:apply`）
2. Javadoc 与方法签名同步更新（实体字段类型变化必须更新注释）
3. 改 energy-common 后先 `mvn install -pl energy-common`
4. Git Bash 用 mvn.cmd；Maven 离线 `-o`，仓库 `-Dmaven.repo.local=D:\Program Files\maven-repo`
5. 每个 Task：implementer 实现 → reviewer 审查（review-package 脚本）→ approve 后进入下个 Task
6. 报告写入 `.superpowers/sdd/2026-08-12-enums-refactor-plan/` 对应 task-N-report.md
