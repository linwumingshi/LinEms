# 业务状态/分类值枚举化设计（前后端）

日期：2026-08-12
状态：设计定稿（用户已确认：全量业务状态枚举化 + 实体字段改枚举）

## 一、背景与目标

当前项目业务状态值散落形态：

- **后端**：仅设备状态（DEVICE_STATUS_*）与指令状态（CMD_STATE_*）有 `Constants.java` int 常量；
  其余全部是**魔法值**（`EmsStrategy.status` 注释"0草稿 1启用 2停用"、`AlarmRecordRow.level` 注释
  "1提示 2一般 3严重 4危急"等），无类型约束，比较全靠字面量，改值/新增值靠人肉对齐。
- **前端**：`utils/dicts.ts` 已集中 text/tag 映射但全是魔法值函数；`models.ts` 字段为 `number`；
  页面仍有零散 options。

目标：
1. 后端业务状态/分类值全部改为 Java 枚举（`code`+`desc`），实体字段 `Integer/String` → 枚举类型；
2. 前端建 TS 枚举（`as const` 对象 + 派生类型），`dicts.ts` 收敛到枚举，`models.ts` 字段类型化；
3. **DB 存储值、JSON 对外值、前端运行时值全部保持不变**（兼容存量数据与在线接口）。

不纳入：基础设施契约常量（Kafka topic、Redis channel、JWT/header 常量）——它们是外部字符串
契约，枚举化无收益且破坏语义。

## 二、枚举清单（全量）

统一放 `energy-common` 模块 `com.energyx.common.enums` 包（common 被所有模块依赖，天然共享）。

### 2.1 整数型状态（code: int，DB 存 code）

| 枚举 | code | 语义 | 使用方 |
|---|---|---|---|
| `DeviceStatus` | 0未注册 1未激活 2已激活离线 3在线 4禁用 5封禁 | 设备生命周期（替代 Constants.DEVICE_STATUS_*） | energy-device `Device.status` |
| `CommandState` | 0已创建 1已发送 2设备已接收 3执行中 4成功 5失败 6超时 | 指令状态（替代 Constants.CMD_STATE_*） | energy-command `CommandRow.state` |
| `StrategyStatus` | 0草稿 1启用 2停用 | 充放电策略 | energy-ems `EmsStrategy.status` |
| `PlanStatus` | 0待执行 1执行中 2完成 3已取消 | 充放电计划 | energy-ems `EmsPlan.status` |
| `PlanPointState` | 0待下发 1已下发 2成功 3失败 4超时 | 计划点执行 | energy-ems `EmsExecutionRecord.state` |
| `ElectricityPriceStatus` | 0停用 1启用 | 电价档案 | energy-ems `EmsElectricityPrice.status` |
| `ConstraintStatus` | 0停用 1启用（实施时以 SQL 注释/使用处确认） | 安全包络约束 | energy-ems `EmsConstraint.status` |
| `AlarmRecordStatus` | 0触发中 1已恢复 2已确认 | 告警记录 | energy-alarm `AlarmRecordRow.status` |
| `AlarmRuleStatus` | 0停用 1启用 | 告警规则 | energy-alarm `AlarmRuleRow.status` |
| `AlarmLevel` | 1提示 2一般 3严重 4危急 | 告警级别 | energy-alarm `AlarmRecordRow.level` |
| `ProductStatus` | 0禁用 1启用 | 产品 | energy-product `Product.status` |
| `ThingModelStatus` | 0草稿 1已发布 2已废弃 | 物模型 | energy-product `ThingModel.status` |
| `StationStatus` | 0停运 1运行 | 电站 | energy-station `Station.status` |
| `UserStatus` | 0禁用 1启用 2锁定 | 用户 | energy-system `SysUser.status` |
| `RoleStatus` | 0禁用 1启用 | 角色 | energy-system `SysRole.status` |
| `PermissionStatus` | 0正常 1停用 | 权限 | energy-system `SysPermission.status` |
| `TenantStatus` | 0禁用 1启用 | 租户 | energy-system `SysTenant.status` |
| `DataScope` | 1本人 2本企业 3本租户 4全部 | 数据范围 | energy-system `SysRole.dataScope` |
| `EnterpriseLevel` | 1集团直属 2子企业 | 企业层级 | energy-system `SysEnterprise.level` |
| `CredentialAuthStatus` | 1正常 2吊销 | 设备凭据 | energy-device `DeviceCredential.authStatus` |

### 2.2 字符串型分类（code: String，DB 存 code）

| 枚举 | code | 语义 | 使用方 |
|---|---|---|---|
| `DeviceType` | ENERGY_CABINET/BATTERY_CLUSTER/PCS/BMS/EMS/EDGE_GW/METER | 设备类型 | energy-device `Device.deviceType` |
| `StrategyType` | PEAK_VALLEY/DEMAND/DR/SOC_CTRL/TIME | 策略类型 | energy-ems `EmsStrategy.strategyType` |
| `PriceType` | DEEP/VALLEY/FLAT/PEAK/PEEK | 电价档位 | energy-ems `EmsElectricityPrice.priceType` |
| `GridType` | 工商业/园区/电网侧 | 电网类型（中文 code，保持原值） | energy-station `Station.gridType` |
| `RevenuePeriodType` | DAY/WEEK/MONTH | 收益统计周期 | energy-ems `RevenueSummary.periodType`（入参） |
| `EventSeverity` | INFO/WARN/ERROR | 事件级别（映射 TDengine 1/2/3） | energy-access `ThingModelEvent.type` |

> 实施时若发现清单外的状态/分类字段，按同模式补充，并在本清单登记。

## 三、后端模式

### 3.1 枚举定义模板（整数型）

```java
package com.energyx.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

/** 设备生命周期状态（替代 Constants.DEVICE_STATUS_*，DB 存 code） */
public enum DeviceStatus {

    UNREGISTERED(0, "未注册"),
    INACTIVE(1, "未激活"),
    OFFLINE(2, "已激活离线"),
    ONLINE(3, "在线"),
    DISABLED(4, "禁用"),
    BANNED(5, "封禁");

    /** 存储值（DB 列值，@EnumValue 让 MyBatis-Plus 按此读写） */
    @EnumValue
    private final int code;

    /** 展示语义（前端/日志/文档共用，与前端 enums.ts 对齐） */
    private final String desc;

    DeviceStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /** JSON 序列化输出 code（数字），保持对外接口值不变 */
    @JsonValue
    public int getCode() {
        return code;
    }

    /** JSON 反序列化入参按 code 还原 */
    @JsonCreator
    public static DeviceStatus fromCode(int code) {
        return Arrays.stream(values()).filter(e -> e.code == code).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知 DeviceStatus code=" + code));
    }

    /** 安全查找：未知/空返回 null（查询/宽容场景用，避免抛错） */
    public static DeviceStatus of(Integer code) {
        if (code == null) {
            return null;
        }
        return Arrays.stream(values()).filter(e -> e.code == code).findFirst().orElse(null);
    }

    public String getDesc() {
        return desc;
    }
}
```

字符串型枚举同构（code 为 String，`@EnumValue` 标注，`fromCode(String)`）。

### 3.2 配置

- MyBatis-Plus 已启用枚举支持：`@EnumValue` 注解后 MP 3.5.x 默认 `MybatisEnumTypeHandler`
  自动处理，无需额外配置；为稳妥在各服务 `application.yml` 加
  `mybatis-plus: type-enums-package: com.energyx.common.enums`（作用于各模块 yml，实施时确认现状）。
- Jackson：`@JsonValue` 让枚举序列化为 code（数字/字符串），与改造前 `Integer/String` 输出
  **完全一致**，前端零感知；`@JsonCreator` 支持前端按 code 传参。

### 3.3 实体字段改造

```java
// 改前
/** 设备状态：0未注册 1未激活 2已激活(离线) 3在线 4禁用 5封禁 */
private Integer status;

// 改后（Javadoc 同步更新，注释仍保留完整语义）
/** 设备生命周期状态（UNREGISTERED/INACTIVE/OFFLINE/ONLINE/DISABLED/BANNED） */
private DeviceStatus status;
```

实体字段类型变更后：
- 业务比较 `device.getStatus() == 2` → `device.getStatus() == DeviceStatus.OFFLINE`
  （枚举单例，`==` 安全；MP 反序列化返回枚举单例，`==`/`equals` 均可用）
- 设置值 `setStatus(2)` → `setStatus(DeviceStatus.OFFLINE)`
- 查询 `eq(Device::getStatus, 2)` → `eq(Device::getStatus, DeviceStatus.OFFLINE)`
  （MP 按 @EnumValue 转存库值）
- 入参 DTO 若接收 status 数字 → 字段类型改枚举，`@JsonCreator` 自动还原

### 3.4 Constants.java 处置

`DEVICE_STATUS_*` / `CMD_STATE_*` 常量**保留**（`@Deprecated` 标注"使用枚举替代"），
供未迁移完的引用编译通过；全部迁移完成后删除。避免一次性大爆炸。

## 四、前端模式

### 4.1 TS 枚举（`src/utils/enums.ts`，新建）

```ts
/** 业务状态/分类枚举（与后端 com.energyx.common.enums 对齐，code 与 DB/接口值一致） */

export const DeviceStatus = {
  UNREGISTERED: 0,
  INACTIVE: 1,
  OFFLINE: 2,
  ONLINE: 3,
  DISABLED: 4,
  BANNED: 5,
} as const
export type DeviceStatus = (typeof DeviceStatus)[keyof typeof DeviceStatus]

/** 枚举 → 展示文案（替代 dicts.ts 中的魔法值 Record/函数） */
export const DEVICE_STATUS_TEXT: Record<DeviceStatus, string> = {
  [DeviceStatus.UNREGISTERED]: '未注册',
  [DeviceStatus.INACTIVE]: '未激活',
  [DeviceStatus.OFFLINE]: '离线',
  [DeviceStatus.ONLINE]: '在线',
  [DeviceStatus.DISABLED]: '禁用',
  [DeviceStatus.BANNED]: '封禁',
}
```

字符串型枚举同构（`as const` 对象值取字符串字面量）。

### 4.2 `dicts.ts` 收敛

`deviceStatusText/deviceStatusTag/stationStatusText/...` 等函数改为从 `enums.ts` 的
`XX_TEXT`/`XX_TAG` Record 取值的封装，签名不变（`text(s: number): string` → 接受枚举类型），
调用方（各页面）**签名兼容、无需改动**。

### 4.3 `models.ts` 字段类型化

```ts
// 改前
status: number
// 改后
status: DeviceStatus
```

运行时值不变（仍为数字），编译期获得类型约束。

### 4.4 页面散落值替换

`Device.vue` 等页面中 `row.status === 1` 类字面量 → 枚举引用（如 `DeviceStatus.INACTIVE`）。

## 五、兼容性保证

| 层 | 保证 |
|---|---|
| DB | `@EnumValue` 存 code，存量数据无迁移 |
| 后端 JSON | `@JsonValue` 输出 code（数字/字符串），对外接口响应体不变 |
| 后端入参 | `@JsonCreator` 按 code 还原，前端传参不变 |
| 前端运行时 | TS 枚举是编译期类型，运行时仍是数字；`models.ts` 改类型不影响运行 |
| 前端展示 | dicts.ts 函数签名保持，各页面调用点不动（除字面量替换任务） |

## 六、改造策略与任务拆分（Subagent-Driven）

| 任务 | 范围 | 产出 |
|---|---|---|
| Task 1 | common 枚举基座：DeviceStatus/CommandState + MP/Jackson 配置 + device/command 模块实体字段改造 + 测试 | 首个枚举落地，建立模式 |
| Task 2 | energy-ems 模块：StrategyStatus/PlanStatus/PlanPointState/ElectricityPriceStatus/ConstraintStatus/PriceType/StrategyType/RevenuePeriodType + 实体/调用方 + 测试 | ems 全枚举 |
| Task 3 | alarm/product/station/system/access 模块：AlarmLevel/AlarmRecordStatus/AlarmRuleStatus/ProductStatus/ThingModelStatus/StationStatus/GridType/UserStatus/RoleStatus/PermissionStatus/TenantStatus/DataScope/EnterpriseLevel/CredentialAuthStatus/DeviceType/EventSeverity + 实体/调用方 + 测试 | 其余全枚举 |
| Task 4 | 前端：enums.ts + dicts.ts 收敛 + models.ts 类型 + 页面字面量替换 | vue-tsc 通过 |
| Task 5 | 全量构建回归：全部模块 `mvn test` + 前端 `vue-tsc` + 关键链路冒烟 | 全绿 |

每个任务独立 commit + 双人审查（implementer + reviewer），按 SDD 流程执行。

## 七、风险与对策

| 风险 | 对策 |
|---|---|
| 改动面大（~20 枚举 × 实体/调用方/测试） | 分模块 Task，逐个 commit；Task 1 建立模式后其余复制 |
| 实体字段类型变更引入编译错误 | 全量 `mvn compile` 分模块验证；Constants 保留过渡 |
| Mapper XML 中枚举参数 | MP @EnumValue 自动转换，实施时检查 XML 动态 SQL |
| 前端 dicts 函数签名变更波及页面 | 保持函数签名（参数放宽为 number 兼容），仅内部用枚举 |
| 枚举 code 与 DB 不一致 | 从实体注释/使用处提炼 code，交叉验证 SQL 种子数据 |
| 服务运行中重构 | 改造期间不重启；完成后统一打包重启 |

## 八、验收口径

1. 后端所有实体 status/state/level/type 等业务字段为枚举类型，`Constants` 无新增魔法值；
2. `mvn test`（全模块）+ 前端 `vue-tsc --noEmit` 全绿；
3. 设备/策略/计划/告警等关键接口 JSON 输出与改造前逐字段一致；
4. DB 存量数据正常读写（枚举 ↔ 存库 code 无损）；
5. 前端各页面展示/操作正常（冒烟：设备状态按钮、策略启用、告警级别标签）。
