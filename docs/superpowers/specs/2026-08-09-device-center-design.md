# 子项目 B · 设备数据中心 — 设备详情「运行状态/物模型」Tab 设计

- 版本：v1.0    日期：2026-08-09
- 上游：ID2 设备数据中心（三子项目之 B，顺序 A→B→C；A 基础档案已完成）
- 关联：[[2026-08-07-sim-device-design]]（sim-device 模拟器与上报链路）

## 1. 背景与目标

设备管理页目前是「表格 + 行点击打开 480px 详情抽屉」，抽屉内仅有描述信息与连接凭据，看不到设备运行数据。本子项目在详情抽屉内新增 **「运行状态/物模型」Tab**，实现：

1. **最新值（运行状态）**：按物模型属性（identifier/name/dataType/unit）展示设备当前上报值（来自影子 reported），含最后上报时间。
2. **历史值查询**：时间范围选择器 + 属性单选 → 折线图 + 数据表格两种形态展示 TDengine 真时序历史。

历史数据源采用 **TDengine 真时序**（用户已选定）。本机需把 TDengine 容器跑起来、按写路径建表、造数，读路径才可验证。

## 2. 范围

### 做
- 设备详情抽屉改 tabs（基本信息 / 运行状态），抽屉加宽至 820px。
- 运行状态 tab：最新值卡片区 + 历史查询区（折线图 + 分页表格）。
- TDengine 本机启用（容器 + 单节点建库建表 + 造数）。
- 后端新增两条读路径：TSL by-key、TDengine 历史查询；网关新增 `/api/tsdb/**` 路由。
- `ShadowView` 增加 `lastReportedTime`（additive）。

### 不做（本子项目范围之外）
- 物模型的事件 / 服务（留给子项目 C IoT 联动）。
- 属性曲线叠加绘制（量纲不同，单选一条曲线）。
- 降采样 / 聚合查询、跨设备/跨电站统计。
- 指令下发、设备运维操作。
- 权限按钮级门控（同电站页，不加）。

## 3. 架构总览

前端为聚合层（与现有代码风格一致），一次详情打开发 3 类请求：

```
设备详情抽屉(820px) ─ el-tabs
  ├─ 基本信息    （现有 descriptions + 凭据，原样保留）
  └─ 运行状态/物模型 (lazy)
       ├─ 最新值卡片区   ← GET /api/shadow/{deviceId}   （reported 快照 + lastReportedTime）
       │                   + GET /api/product/thing-model/by-key?productKey= （TSL 属性名/单位）
       └─ 历史值查询区   ← GET /api/tsdb/property/history（TDengine 宽表，折线图 + 分页表格）
```

后端新增：

```
energy-product：GET /api/product/thing-model/by-key?productKey=   → ThingModelView（schemaJson 原样）
energy-tsdb：   GET /api/tsdb/property/history?...                → PropertyHistoryView（分页）
energy-gateway：/api/tsdb/** 路由（StripPrefix=1，controller 映射 /tsdb）
```

数据流（已有，本子项目不改写路径）：

```
设备上报 ─MQTT→ broker ─Kafka(mqtt.router)→ energy-access ─Kafka(iot-thing-property)→
   ├─→ energy-shadow（最新值：Redis + MySQL iot_shadow）
   └─→ energy-tsdb（时序：TDengine iot_tsdb_raw.st_prop_{productKey} 宽表，子表 dev_{deviceId}）
```

## 4. 现状与数据源（已核实事实）

### 4.1 物模型 TSL
- 存储：MySQL `es_product.iot_thing_model`，`schema_json` 为完整 JSON 快照（properties/services/events），另有 `iot_thing_model_identifier` 投影表。
- 现有接口：`GET /api/product/{productId}/thing-model`（按 **productId**）。**设备只有 productKey，无 productId** → 需新增 by-key 入口。
- 种子产品 `snd_ess_pcs`（product_id=1）属性：`soc`(荷电状态,float,%)、`voltage`(母线电压,V)、`current`(母线电流,A)、`power`(功率,kW)、`temp`(温度)、`runMode`(运行模式,int)。

### 4.2 最新值
- `GET /api/shadow/{deviceId}` 返回 `ShadowView{deviceId,reported,desired,version}`；`reported` 为最新全量快照（Redis 热路径 → MySQL 回源）。
- 现 `ShadowView` **无时间字段**；MySQL `iot_shadow.last_reported_time` 已有 → 增加 `lastReportedTime` additive 字段。
- 实测（2026-08-09）：`sim-dev-000001`（deviceId `8000000000000000001`）reported 含 6 属性 `{soc:86.0,temp:34.0,power:1031.0,current:18.0,runMode:1,voltage:204.0}`，状态 3（在线）。

### 4.3 历史时序（TDengine 写路径现状）
- `energy-tsdb`（端口 8112）`PropertyTsdbConsumer` 消费 `iot-thing-property` → `TdengineSqlBuilder.buildPropertyInsert` → `TdengineWriter.execute`（进程级单连接，TAOS-RS JDBC）。
- **写路径列名 = 物模型 identifier 原样（反引号包裹）**，公共列 `ts/msg_id/data_type`；stable `st_prop_{productKey}`，子表 `dev_{deviceId}`；TAGS `device_id/station_id/enterprise_id/product_key`。
- **本机实测（2026-08-09）**：TDengine 容器 `ems-tdengine` **已在运行**（Up 21h，6030/6041 监听，REST `root/taosdata` 可查），`energy-tsdb` 与其建立 JDBC 连接（6041 上 ESTABLISHED）；`iot_tsdb_raw` 库与 `st_prop_snd_ess_pcs` stable 已存在（线上为单节点实例，建库参数已适配）。写路径真实在落：`dev_8000000000000000001` 有 2 行（2026-08-07/08，msg_id 形如 `snd_ess_pcs_sim-dev-000001_N`，经 sim-device 真实上报）。
- **列名不一致 = 现存数据丢失根因（须修正）**：线上 stable 属性列是 `run_mode`（snake_case），而写路径写 `runMode`。凡上报含 `runMode` 的报告（如 2026-08-09 11:56 的 `{soc,temp,power,current,runMode,voltage}`）被 TDengine 以「列不存在」拒绝——**影子有当日数据、TDengine 无当日行**。须 `ALTER STABLE ... ADD COLUMN runMode INT` 与写路径对齐，并同步修正 `10/20_stable.sql`。
- 历史目前极稀疏（每设备 2 行左右），读接口开发须造数。

### 4.4 网关路由约定
- StripPrefix=1（controller 不写 `/api`）：`/api/system|product|device|station|ems/**`。
- 不 StripPrefix（controller 自带 `/api`）：`/api/shadow|command|alarm/**`。
- 新增 `/api/tsdb/**` 走 StripPrefix=1，controller `@RequestMapping("/tsdb")`。

## 5. TDengine 现状核对与修复（前置，实施首个任务）

TDengine 容器 `ems-tdengine` **已在运行**（Up 21h，6030/6041 监听，`energy-tsdb` 已建连）。`iot_tsdb_raw` 库与 `st_prop_snd_ess_pcs` stable 已存在（单节点实例，建库参数已适配，`REPLICA` 非 2）。前置任务只剩**修复列名不一致 + 造数**：

1. **修复列名不一致（数据丢失根因）**：为线上 stable 补 `runMode` 列，与写路径对齐：
   - `ALTER STABLE iot_tsdb_raw.st_prop_snd_ess_pcs ADD COLUMN runMode INT;`（REST：`curl -s -u root:taosdata -d 'ALTER STABLE ...' http://127.0.0.1:6041/rest/sql`）
   - 同步修正 `sql/tdengine/10_stable.sql`、`20_sample_stable.sql`：`run_mode` 改 `runMode`，并加注释「属性列名 = TSL identifier 原样，须与写路径一致」；线上旧列 `run_mode` 保留不再写入。
   - 注：`iot_tsdb_event`/`iot_tsdb_agg` 库未建——B 只读 raw 属性历史，不需要；事件库留待子项目 C 或需要时再建。
2. **造数**（验证写路径 + 供读路径/冒烟）：
   - 主路径（确定性）：对 `iot_tsdb_raw.st_prop_snd_ess_pcs` 插 `dev_8000000000000000001` 分钟级点（ts/msg_id/data_type/soc/voltage/current/power/temp/runMode），覆盖近 24h 至最近几分钟。
   - 全链路核对：`sim-device` 发一条**含 runMode** 的 `report`（如 `report soc=86 temp=34 power=1031 current=18 runMode=1 voltage=204`）→ 确认新行带 runMode 值（修复后不再被拒）。
   - 验证：`SELECT count(*) FROM iot_tsdb_raw.st_prop_snd_ess_pcs WHERE device_id='8000000000000000001'` 递增；`energy-tsdb` 日志无「列不存在」错误（写路径自动重建连接，无需重启服务）。

## 6. 后端设计

### 6.1 energy-product：TSL by-key（小改动）
- `ProductService` 接口新增 `ThingModelView getThingModelByProductKey(String productKey)`；
- `ProductServiceImpl` 实现：`productMapper.selectOne(product_key = ? AND deleted = 0)` → 无则返回 null；否则复用 `getThingModel(productId)`；
- `ProductController` 新增 `@GetMapping("/thing-model/by-key")` 接收 query 参数 `productKey`，返回 `Result<ThingModelView>`，null → `Result.fail(NOT_FOUND, "产品未发布物模型或不存在：{productKey}")`。
- 路由无冲突：`/product/thing-model/by-key`（3 段，首段字面量）优先于 `/product/{productId}/thing-model`（3 段，首段变量）匹配。

### 6.2 energy-tsdb：历史查询（新 controller + query service + 纯 SQL 构建器）
- 新增 `web/TsdbController`：`@RestController @RequestMapping("/tsdb")`；
  - `@GetMapping("/property/history")` 入参（query）：
    - `deviceId`（必填，string）
    - `productKey`（必填，string，须 `TdengineSqlBuilder.isSafeKey`）
    - `identifiers`（必填，逗号分隔，≤10 个，空则 400）
    - `startTime`/`endTime`（可选，epoch 毫秒 number；缺省近 24h）
    - `order`（`asc`|`desc`，默认 `desc`；图表用 asc、表格用 desc）
    - `page`/`size`（默认 1/20；size 1–1000）
  - 返回 `Result<PropertyHistoryView>`。
- 新增 `sql/TdengineQuerySqlBuilder`（纯函数，无副作用，照 `TdengineSqlBuilder` 模式，便于单测）：
  - 输入：db、productKey、identifiers（白名单过滤后）、deviceId、startTime/endTime、order、offset、limit；
  - 输出：`{ dataSql, countSql }` 或两个独立方法；
  - `dataSql`：`SELECT ts, ` + 白名单内标识符列（反引号包裹） + ` FROM {db}.st_prop_{productKey} WHERE device_id = ? AND ts >= ? AND ts <= ? ORDER BY ts {ASC|DESC} LIMIT ? OFFSET ?`；
  - `countSql`：`SELECT count(*) FROM {db}.st_prop_{productKey} WHERE device_id = ? AND ts >= ? AND ts <= ?`；
  - **identifier 白名单**：先 `DESCRIBE {db}.st_prop_{productKey}`（结果缓存，TTL 60s）取合法列名集合（排除 ts/msg_id/data_type 公共列），请求的 identifiers 仅保留在白名单内的；过滤后为空 → 抛参数异常（400）。
- 新增 `service/TdengineQueryService`：懒连接（照 `TdengineWriter` 单连接模式，DriverManager + 失败重建重试一次）；`PreparedStatement` 绑定 `?`（device_id 字符串、ts 毫秒 long、offset/limit int）；执行 dataSql + countSql；读 ResultSet 组装 `PropertyHistoryRecord`；**某行属性列为 NULL（该设备未上报该属性）时 `values` 省略该键或置 null**。
- DTO（`web/dto`）：
  - `PropertyHistoryView { String deviceId; String productKey; long total; List<PropertyHistoryRecord> records; }`
  - `PropertyHistoryRecord { long ts; Map<String, Object> values; }`
  - `ts`/`total` 用 **primitive long**（JacksonConfig 只装箱 Long→字符串，primitive 保持数字，见全局约束）。
- 连接密码从 Nacos `energyx.tsdb.jdbc-password` 注入（复用 `TsdbProperties`）。

### 6.3 energy-gateway：新路由
`backend/energy-gateway/src/main/resources/application.yml` 的 `spring.cloud.gateway.routes` 追加：

```yaml
        - id: energy-tsdb
          uri: lb://energy-tsdb
          predicates:
            - Path=/api/tsdb/**
          filters:
            - StripPrefix=1
```

### 6.4 energy-shadow：ShadowView.lastReportedTime（additive）
- `ShadowView` DTO 增 `String lastReportedTime`；
- `ShadowService.getShadow` 从查询结果映射（`ShadowMapper` 已查 `last_reported_time` 则直接带出，否则补列）；Redis 热路径对象同步携带。
- 兼容性：仅新增字段，前端旧逻辑不受影响。

## 7. 前端设计

### 7.1 类型与 API 层
- `src/types/models.ts` 新增：
  ```ts
  export interface TsProperty {
    identifier: string
    name: string
    dataType: string
    unit?: string
    accessMode?: string
  }
  export interface PropertyHistoryRecord {
    ts: number // epoch 毫秒
    values: Record<string, number | string | null>
  }
  export interface PropertyHistoryView {
    deviceId: string
    productKey: string
    total: number
    records: PropertyHistoryRecord[]
  }
  export interface ThingModelSchema {
    properties: TsProperty[]
    services: unknown[]
    events: unknown[]
  }
  ```
  `ShadowView` 增 `lastReportedTime?: string`。
- `src/api/product.ts` 新增：
  ```ts
  thingModelByKey(productKey: string): Promise<ThingModelView>
  // GET /api/product/thing-model/by-key, params: { productKey }
  ```
- 新 `src/api/tsdb.ts`：
  ```ts
  export interface TsHistoryParams {
    deviceId: string
    productKey: string
    identifiers: string[]
    startTime?: number // epoch 毫秒
    endTime?: number
    order?: 'asc' | 'desc'
    page?: number
    size?: number
  }
  export const tsdbApi = {
    propertyHistory(params: TsHistoryParams): Promise<PropertyHistoryView>
    // GET /api/tsdb/property/history, params 序列化：identifiers join(',')
  }
  ```

### 7.2 物模型解析工具（新 `src/utils/thingModel.ts`）
- `parseThingModel(schemaJson: string): ThingModelSchema`：`JSON.parse`，`properties/services/events` 缺省空数组；解析失败返回空结构（不影响页面）。

### 7.3 Device.vue 抽屉改造
- 抽屉 `size` 480 → **820px**；`openDetail` 时重置运行状态数据源（`runtimeState` 清空），保证打开不同设备时重新加载。
- 抽屉内容改为 `el-tabs`（`v-model="activeTab"`，默认 `basic`）：
  - `el-tab-pane name="basic" label="基本信息"`：现有 `el-descriptions` + 凭据卡片，原样搬入。
  - `el-tab-pane name="runtime" label="运行状态" lazy`：本 tab 的 UI 与逻辑（见 7.4）。`lazy` 保证首次激活才挂载 → 图表容器可见后 `useEChart` 正常初始化。
- 运行状态数据加载用 `watch([detail, activeTab], ...)`：当 `activeTab === 'runtime'` 且当前设备未加载时并行拉 `shadowApi.getShadow` + `productApi.thingModelByKey(device.productKey)`；任一步失败 `ElMessage.error` 并在该区显示空态。

### 7.4 运行状态 tab UI
- **最新值卡片区**：网格展示物模型属性（`TsProperty[]`）：每卡「属性名 / 值 + 单位 / identifier」，值 = `shadow.reported[identifier] ?? '—'`；`runMode` 等 enum/int 显示原始值（枚举文本映射不做，留 C）。顶部一行显示「最后上报：`toLocal(shadow.lastReportedTime)`」。
- **历史值查询区**：
  - 控件行：`el-date-picker type="datetimerange"`（默认近 24h，`value-format="YYYY-MM-DDTHH:mm:ss"`，query 前转 epoch 毫秒）+ 属性单选 `el-select`（选项 = TSL 属性，默认第一个）+ `[查询]` 按钮。
  - 折线图：容器 `div ref="chartEl"`（高 ~300px），`useEChart(chartEl)`；查询 → `tsdbApi.propertyHistory({ order:'asc', page:1, size:1000 })` → records 映射 `[{ts, value}]` → 单 series line chart；x 轴时间（`tsToLocal` 格式）、y 轴「值 + 单位」；无数据 `chart.clear()` + 图下空态文案。
  - 数据表格：`el-table`（列：时间/所选属性值）+ `el-pagination`（`total` 由接口返回，翻页以 `order:'desc'` 重新查询）。
  - 同一查询点击只发两个请求（图表 size=1000 asc + 表格 page/size desc）。

## 8. 数据契约（汇总）

| 接口 | 方法/路径 | 关键参数 | 返回 |
|---|---|---|---|
| TSL by-key | `GET /api/product/thing-model/by-key?productKey=` | productKey | `ThingModelView`（schemaJson 原样） |
| 历史查询 | `GET /api/tsdb/property/history` | deviceId, productKey, identifiers(≤10), startTime/endTime(ms), order, page, size | `{deviceId, productKey, total, records:[{ts, values}]}` |
| 最新值 | `GET /api/shadow/{deviceId}`（已有） | deviceId | `ShadowView`（reported/desired/version + **lastReportedTime**） |

- 时间一律 **epoch 毫秒**（TDengine ts 原生 ms，无时区歧义）；前端展示用 `tsToLocal`/`toLocal`。
- `ts`/`total` 为 primitive long（序列化保持数字）。

## 9. 错误处理与空态

| 场景 | 前端表现 |
|---|---|
| TDengine 不可用 / 查询 500 | `ElMessage.error('历史数据查询失败')`；图表清空、表格空 |
| TSL 未发布 / productKey 不存在（404） | 运行状态 tab 显示空态 + 提示「产品未发布物模型」；历史查询区隐藏 |
| 所选属性在时间范围内无数据 | 图表空（`clear()`）、表格 `empty-text`「暂无数据」 |
| shadow reported 为空 / 无 lastReportedTime | 卡片值 `—`，最后上报 `—` |
| identifiers 全被白名单过滤（400） | `ElMessage.error`，不发起查询 |

## 10. 测试策略

- **后端单测**（不依赖 TDengine 实例）：
  - `TdengineQuerySqlBuilderTest`：dataSql/countSql 含反引号列、identifier 白名单过滤（非法/未知列剔除）、deviceId/时间参数为 `?` 占位、order/LIMIT/OFFSET 正确；
  - `ProductServiceImplTest`：`getThingModelByProductKey`（product 存在→view；product 不存在→null；无 current 模型→null）——mapper mock；
  - `TsdbController` 参数校验：identifiers 空/超 10、size 越界 → 400。
- **前端**：
  - `thingModel.ts` vitest：合法 schema 解析 / 畸形 JSON → 空结构；
  - `tsdb.ts`：identifiers 数组 join 序列化断言；
  - `vue-tsc --noEmit` 0 错误。
- **浏览器冒烟**（Playwright + Edge 无头，BASE `:25173`/网关 `:8000`）：
  1. 登录 → 设备管理 → 表格中找到 `sim-dev-000001`（或按 deviceId 搜索）→ 点「详情」；
  2. 断言抽屉宽度/「运行状态」tab 存在 → 点击 → 卡片区出现 `soc` 且值非 `—`、最后上报非空；
  3. 选属性 + 时间范围（覆盖造数窗口）→ 查询 → 断言图表 canvas 节点存在、表格 ≥1 行；
  4. 翻页 / 切换时间范围（无数据窗口）→ 空态正确。
  - 冒烟前置：TDengine 已起 + `dev_8000000000000000001` 已造数（见 §5）。

## 11. 全局约束（实施时逐条遵守）

- **提交红线**：不 `git add -A`，一律显式 `:/路径` pathspec；绝不提交 `backend/energy-mqtt-broker/.../BrokerProperties.java` 与 `frontend/vite.config.ts`（本机专属，始终 M）。
- **网关路由约定**：新增 `/api/tsdb/**` StripPrefix=1，controller 映射 `/tsdb` 不带 `/api`。
- **雪花 Long → string**：JacksonConfig 装箱 Long→ToStringSerializer；**primitive long 保持数字**（`ts`/`total` 用 primitive）。
- **前端 id 均为 string**（deviceId/productId）。
- **ObjectMapper FAIL_ON_UNKNOWN off**：后端请求体只收 DTO 字段，历史查询全部走 query 参数，无需新增 request body DTO。
- **productKey 可含 `_`**（如 `snd_ess_pcs`）；列名 = TSL identifier 原样，反引号包裹 + DESCRIBE 白名单，禁止拼接未校验列名。
- **TDengine 单节点 REPLICA 1**（生产集群 REPLICA 2，见 00_database.sql）。
- 界面文案中文；验证命令在 `frontend/` 目录跑（`cd "D:/.../frontend" && npx vue-tsc --noEmit`、`npm test`）。

## 12. 实施任务（供 writing-plans 拆解）

1. TDengine 现状核对与修复：`ALTER` 补 `runMode` 列 → 修正 `10/20_stable.sql`（runMode）→ 造数（INSERT 分钟级点 + sim-device 含 runMode 全链路核对）。
2. energy-product：TSL by-key 接口 + 单测。
3. energy-tsdb：`TdengineQuerySqlBuilder`（纯函数+单测）→ `TdengineQueryService` → `TsdbController` → 网关路由。
4. energy-shadow：`ShadowView.lastReportedTime`（additive）。
5. 前端类型/API/工具：`models.ts` 类型、`product.ts` thingModelByKey、`tsdb.ts`、`thingModel.ts` + vitest。
6. Device.vue：抽屉 tabs + 运行状态 UI（卡片 + 图表 + 表格 + 空态/错误处理）。
7. 验证：vue-tsc、vitest、浏览器冒烟；写冒烟报告。

> 本设计为子项目 B 唯一事实来源；与代码现状冲突处以本文件为准，发现矛盾先上报。
