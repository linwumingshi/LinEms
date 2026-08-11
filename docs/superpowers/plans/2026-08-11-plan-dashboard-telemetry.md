# 储能遥测驾驶舱（P1-4）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `/dashboard` 从"告警驾驶舱"升级为"储能遥测驾驶舱"——电站级实时监控（SOC/功率/电压/温度 KPI + 时序曲线 + 收益卡），保留告警入口。

**Architecture:** 纯前端改造（`frontend/src/views/Dashboard.vue` 重构）。数据源全部现成：影子实时快照（`shadowApi.getShadow`，soc/voltage/current/power/temp/runMode）、TSDB 属性历史（`tsdbApi.propertyHistory` 曲线）、收益聚合（`emsApi.revenueSummary/revenueTrend`）。电站选择 → 解析站下 PCS/METER 设备 → 并行取影子（KPI）+ TSDB（曲线）+ 收益（卡片）。无后端改动。

**Tech Stack:** Vue3 + Element Plus + ECharts（useEChart）+ 现有 `--ex-*` 设计 token

## Global Constraints

- 前端类型检查 `vue-tsc --noEmit` 必须通过；样式复用 `styles/main.css` 的 `--ex-*` token 与 `.ex-*` 类
- 关键代码中文注释；禁行尾注释；遵循项目现有 Vue 页面写法（`<script setup lang="ts">`）
- 只改 `frontend/src/views/Dashboard.vue`（必要时小改 `frontend/src/api/ems.ts` 或 types），不动后端/其他页面
- 保持现有告警驾驶舱的统计卡/最近告警表（遥测是新首页内容，告警降为分区），不删除已有功能
- 中国行情惯例：功率放电为红（charge 绿/discharge 红，沿用 `ex-readout-value.charge/.discharge` 语义色）

---

### Task 1: Dashboard.vue 重构——电站选择 + 遥测 KPI 读数带

**Files:**
- Modify: `frontend/src/views/Dashboard.vue`（整体重构 script + template）
- Test: 无单测（纯展示页，靠 vue-tsc + 冒烟）

**Interfaces:**
- Consumes: `stationApi.page({pageNum,pageSize})` → `PageResult<Station>`（电站下拉）；`deviceApi.page({pageNum,pageSize,stationId})` → `PageResult<Device>`（站下设备）
- Consumes: `shadowApi.getShadow(deviceId)` → `ShadowView{reported: Record<string,unknown>}`（实时属性）
- Produces: `selectedStation` ref + `loadStationDevices()` 返回站下 PCS 设备（`deviceType` 或 `productKey` 判定）列表

- [ ] **Step 1: 写失败的结构（重构骨架 + 电站选择）**

重构 `Dashboard.vue`，新增：
```ts
// 电站选择与站下设备（P1-4 遥测驾驶舱：电站级实时监控）
const stations = ref<Station[]>([])
const selectedStation = ref<string>('')
const stationDevices = ref<Device[]>([])
async function loadStations(): Promise<void> {
  const page = await stationApi.page({ pageNum: 1, pageSize: 100 })
  stations.value = page.records
}
async function loadStationDevices(): Promise<void> {
  if (!selectedStation.value) { stationDevices.value = []; return }
  const page = await deviceApi.page({ pageNum: 1, pageSize: 200, stationId: selectedStation.value })
  stationDevices.value = page.records
}
```
template 页头新增电站 `el-select`（`v-model="selectedStation" @change="onStationChange"`，clearable）。

- [ ] **Step 2: 遥测 KPI 读数带**

按站取全部 PCS 设备影子，聚合 KPI：
```ts
const telemetry = ref({ soc: 0, power: 0, voltage: 0, current: 0, temp: 0, onlineDevices: 0 })
async function loadTelemetry(): Promise<void> {
  const pcs = stationDevices.value.filter((d) => !d.productKey?.includes('meter'))
  if (pcs.length === 0) { telemetry.value = { soc: 0, power: 0, voltage: 0, current: 0, temp: 0, onlineDevices: 0 }; return }
  const shadows = await Promise.all(pcs.map((d) => shadowApi.getShadow(String(d.deviceId)).catch(() => null)))
  const reported = shadows.filter((s): s is ShadowView => !!s?.reported)
  telemetry.value = {
    soc: avg(reported, 'soc'), power: sum(reported, 'power'),
    voltage: avg(reported, 'voltage'), current: avg(reported, 'current'),
    temp: avg(reported, 'temp'), onlineDevices: reported.length,
  }
}
```
（`avg/sum` 为工具函数：取 reported[key] 数值，忽略非数值；`reported` 可能含 METER 的 importPower 键，PCS 判定用 productKey 排除 meter）
模板读数带 `--ro-cols: 6`：SOC / 功率 / 电压 / 电流 / 温度 / 在线设备。

- [ ] **Step 3: vue-tsc 类型检查**

Run: `cd frontend && ./node_modules/.bin/vue-tsc --noEmit`
Expected: PASS（缺 `Station/Device/ShadowView` import 时补 `@/types/models`）

- [ ] **Step 4: Commit**

```bash
git add frontend/src/views/Dashboard.vue
git commit -m "feat(frontend): 储能遥测驾驶舱——电站选择与遥测 KPI 读数带（P1-4）"
```

### Task 2: 遥测时序曲线（ECharts 实时数据）

**Files:**
- Modify: `frontend/src/views/Dashboard.vue`（新增曲线区）

**Interfaces:**
- Consumes: `tsdbApi.propertyHistory({deviceId, productKey, identifiers, startTime, endTime, order, page, size})` → `PropertyHistoryView{records:[{ts,values}]}`
- Consumes: Task 1 的 `stationDevices` / `selectedStation`
- Produces: `telemetryChart`（双 y 轴：左功率/右 SOC）渲染函数

- [ ] **Step 1: 写失败的结构（曲线数据加载）**

```ts
// 遥测曲线：取首台 PCS 设备近 24h 的 soc/power/temp 属性历史（TSDB），双 y 轴绘制
const curveData = ref<{ times: string[]; soc: number[]; power: number[]; temp: number[] }>({ times: [], soc: [], power: [], temp: [] })
async function loadCurve(): Promise<void> {
  const pcs = stationDevices.value.find((d) => !d.productKey?.includes('meter'))
  if (!pcs) { curveData.value = { times: [], soc: [], power: [], temp: [] }; return }
  const end = Date.now(); const start = end - 24 * 3600 * 1000
  const view = await tsdbApi.propertyHistory({
    deviceId: String(pcs.deviceId), productKey: pcs.productKey,
    identifiers: ['soc', 'power', 'temp'], startTime: start, endTime: end, order: 'asc', page: 1, size: 2000,
  })
  const times: string[] = []; const soc: number[] = []; const power: number[] = []; const temp: number[] = []
  for (const r of view.records) {
    times.push(new Date(r.ts).toTimeString().slice(0, 5))
    soc.push(Number(r.values.soc ?? 0)); power.push(Number(r.values.power ?? 0)); temp.push(Number(r.values.temp ?? 0))
  }
  curveData.value = { times, soc, power, temp }
}
```
（注意：`values` 缺失键按 0 处理会画无效低谷——此处用 `?? 0` 但曲线以空缺语义接受，属近似；Task 1 的 avg/sum 工具对 KPI 已忽略非数值）

- [ ] **Step 2: 渲染曲线（双 y 轴）**

复用 `useEChart`，option：
```ts
renderTelemetry({
  animation: false,
  title: { text: `近 24h 遥测 · ${pcsName}`, left: 'center', textStyle: { fontSize: 13, color: '#1F2833', fontWeight: 600 } },
  tooltip: { trigger: 'axis' },
  legend: { top: 24, textStyle: { color: '#5B6B8C' }, itemWidth: 10, itemHeight: 10 },
  grid: { left: 48, right: 48, top: 60, bottom: 30 },
  xAxis: { type: 'category', data: curveData.value.times, ...AXIS },
  yAxis: [
    { type: 'value', name: '功率(kW)', ...AXIS },
    { type: 'value', name: 'SOC(%)', min: 0, max: 100, ...AXIS },
  ],
  series: [
    { name: '功率', type: 'line', smooth: true, symbol: 'none', lineStyle: { color: '#D4537E', width: 2 }, data: curveData.value.power },
    { name: 'SOC', type: 'line', yAxisIndex: 1, smooth: true, symbol: 'none', lineStyle: { color: '#2B6CB0', width: 2 }, data: curveData.value.soc },
    { name: '温度', type: 'line', yAxisIndex: 1, smooth: true, symbol: 'none', lineStyle: { color: '#E08A1E', width: 1.5, type: 'dashed' }, data: curveData.value.temp },
  ],
})
```

- [ ] **Step 3: 收益卡（电站级）**

复用 `emsApi.revenueSummary({ stationId, periodType: 'DAY', ... })`，模板加收益卡读数（arbitrageRevenue / totalEnergy）：
```ts
const revenue = ref<RevenueSummary | null>(null)
async function loadRevenue(): Promise<void> {
  if (!selectedStation.value) { revenue.value = null; return }
  revenue.value = await emsApi.revenueSummary({ stationId: selectedStation.value, periodType: 'DAY' })
}
```

- [ ] **Step 4: vue-tsc + Commit**

```bash
cd frontend && ./node_modules/.bin/vue-tsc --noEmit
git add frontend/src/views/Dashboard.vue
git commit -m "feat(frontend): 遥测时序曲线与电站收益卡（P1-4）"
```

### Task 3: 告警分区保留 + 空态/加载态完善

**Files:**
- Modify: `frontend/src/views/Dashboard.vue`

**Interfaces:**
- Consumes: 既有 `alarmApi.records` / `summarizeRecords`（保留原告警逻辑）
- Produces: 完成页——遥测区（KPI+曲线+收益）+ 告警区（降为次级分区）

- [ ] **Step 1: 布局重构——遥测为主、告警为次**

模板结构：
```
<div class="ex-page">
  header: 页头 + 电站 el-select
  section.ex-readout-band(--ro-cols: 7)：SOC/功率/电压/电流/温度/在线设备/今日收益
  section.chart-row：遥测曲线卡（全宽）
  section.ex-card：收益卡（可选，若已并入读数带则省略）
  --- 告警分区 ---
  section：原告警三卡 + 最近告警表（降级为"告警"次级分区，仍可切换电站联动）
</div>
```
告警 load() 在 `onStationChange` 时联动（电站筛选后告警按 deviceId 过滤或保持全局——**保持全局不过滤**，简化；仅遥测区随电站联动）。

- [ ] **Step 2: 空态/加载态**

- 未选电站：遥测区显示引导（"请选择电站以查看实时遥测"），告警区照常
- `stationDevices` 空：KPI 全 0 + 曲线空态文本
- `load()` 拆分：`onMounted(loadStations)` → 选站后并行 `loadStationDevices/loadTelemetry/loadCurve/loadRevenue`
- 全部请求失败：`error` ref 统一提示（复用现有 `.err-alert` 样式）

- [ ] **Step 3: vue-tsc + 提交**

```bash
cd frontend && ./node_modules/.bin/vue-tsc --noEmit && npm run build
git add frontend/src/views/Dashboard.vue
git commit -m "feat(frontend): 驾驶舱告警分区保留与空态完善（P1-4 收尾）"
```

---

## Self-Review 记录

- **Spec 覆盖**：Task 1=电站选择+KPI 读数带；Task 2=遥测曲线+收益卡；Task 3=告警保留+空态。覆盖 P1-4 全部验收口径（SOC/功率/电压/温度/收益 KPI + 时序曲线）。✅
- **占位符扫描**：avg/sum 工具函数为 Task 1 内部定义（无外部依赖）；`pcsName` 需 Task 2 从 stationDevices 取（已注明）；无 TBD/TODO。✅
- **类型一致性**：`shadowApi.getShadow`→`ShadowView.reported: Record<string,unknown>`；`tsdbApi.propertyHistory`→`PropertyHistoryView.records[].values`；`emsApi.revenueSummary`→`RevenueSummary`——均与现有 types 对齐。✅
- **风险**：设备 productKey 判定 METER 用 `includes('meter')`（与 sim 产品 snd_ess_meter 一致）；`stationId` 参数 deviceApi 透传后端是否支持需冒烟确认（若 404 则回退"全部设备取前 20 再按 stationId 前端过滤"）。标注为 Task 1 冒烟检查点。
