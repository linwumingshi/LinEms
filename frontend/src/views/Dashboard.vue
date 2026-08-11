<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { alarmApi } from '@/api/alarm'
import { deviceApi } from '@/api/device'
import { emsApi } from '@/api/ems'
import { shadowApi } from '@/api/shadow'
import { stationApi } from '@/api/station'
import { tsdbApi } from '@/api/tsdb'
import AlarmLevelTag from '@/components/AlarmLevelTag.vue'
import { useEChart } from '@/composables/useEChart'
import { levelText, statusTag, statusText, summarizeRecords, toLocal, typeText } from '@/utils/alarmFormat'
import type { AlarmRecord, Device, RevenueSummary, ShadowView, Station } from '@/types/models'

const loading = ref(false)
const error = ref('')
const records = ref<AlarmRecord[]>([])

/** 精确口径卡片：按状态各查一页（取 total） */
const stats = ref({ active: 0, recovered: 0, acked: 0 })

// 图表容器 refs
const trendEl = ref<HTMLElement>()
const levelEl = ref<HTMLElement>()
const statusEl = ref<HTMLElement>()
const { render: renderTrend } = useEChart(trendEl)
const { render: renderLevel } = useEChart(levelEl)
const { render: renderStatus } = useEChart(statusEl)

const recentAlarms = ref<AlarmRecord[]>([])

/** 仪器图表刻度：发丝线、油墨次色、等宽刻度，无多余动效 */
const AXIS = {
  axisLine: { lineStyle: { color: '#DCE2EA' } },
  axisTick: { show: false },
  axisLabel: { color: '#5B6B8C', fontFamily: 'Cascadia Mono, Consolas, monospace' },
  splitLine: { lineStyle: { color: '#ECF0F5' } },
}
/** 告警级别语义色（提示/一般/严重/危急） */
const LEVEL_COLOR: Record<number, string> = {
  1: '#94A0AC',
  2: '#2B6CB0',
  3: '#E08A1E',
  4: '#D64541',
}
/** 告警状态语义色（触发中/已恢复/已确认） */
const STATUS_COLOR = ['#D64541', '#2E9E5B', '#5B6B8C']

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    // 1) 精确总口径（每状态一条查询，size=1 仅取 total）
    const [r0, r1, r2, sample] = await Promise.all([
      alarmApi.records({ status: 0, page: 1, size: 1 }),
      alarmApi.records({ status: 1, page: 1, size: 1 }),
      alarmApi.records({ status: 2, page: 1, size: 1 }),
      alarmApi.records({ page: 1, size: 500 }),
    ])
    stats.value = { active: r0.total, recovered: r1.total, acked: r2.total }
    records.value = sample.records
    recentAlarms.value = sample.records.slice(0, 10)
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
    ElMessage.error(`驾驶舱数据加载失败：${error.value}`)
  } finally {
    loading.value = false
  }
}

// 样本窗口聚合（口径：近 500 条样本）
const summary = ref(summarizeRecords([]))
watch(
  records,
  (list) => {
    summary.value = summarizeRecords(list)
    const s = summary.value

    renderTrend({
      animation: false,
      title: { text: '近 7 日告警趋势（样本）', left: 'center', textStyle: { fontSize: 13, color: '#1F2833', fontWeight: 600 } },
      tooltip: { trigger: 'axis' },
      grid: { left: 42, right: 18, top: 42, bottom: 30 },
      xAxis: { type: 'category', data: s.trend.map((t) => t.day), ...AXIS },
      yAxis: { type: 'value', minInterval: 1, ...AXIS },
      series: [
        {
          name: '触发数',
          type: 'line',
          smooth: true,
          symbol: 'none',
          lineStyle: { color: '#2E9E5B', width: 2 },
          areaStyle: { color: 'rgba(46,158,91,0.12)' },
          data: s.trend.map((t) => t.count),
        },
      ],
    })

    const levelSeries = Object.keys(s.levelCount)
      .map(Number)
      .sort((a, b) => a - b)
      .map((lv) => ({
        name: levelText(lv),
        value: s.levelCount[lv],
        itemStyle: { color: LEVEL_COLOR[lv] ?? '#94A0AC' },
      }))
    renderLevel({
      animation: false,
      title: { text: '告警级别分布（样本）', left: 'center', textStyle: { fontSize: 13, color: '#1F2833', fontWeight: 600 } },
      tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
      legend: { bottom: 0, textStyle: { color: '#5B6B8C' }, itemWidth: 10, itemHeight: 10 },
      series: [{ name: '级别', type: 'pie', radius: ['38%', '62%'], itemStyle: { borderColor: '#fff', borderWidth: 2 }, label: { color: '#5B6B8C' }, data: levelSeries }],
    })

    renderStatus({
      animation: false,
      title: { text: '告警状态分布（样本）', left: 'center', textStyle: { fontSize: 13, color: '#1F2833', fontWeight: 600 } },
      tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
      legend: { bottom: 0, textStyle: { color: '#5B6B8C' }, itemWidth: 10, itemHeight: 10 },
      series: [
        {
          name: '状态',
          type: 'pie',
          radius: ['38%', '62%'],
          itemStyle: { borderColor: '#fff', borderWidth: 2 },
          label: { color: '#5B6B8C' },
          data: [
            { name: '触发中', value: s.active, itemStyle: { color: STATUS_COLOR[0] } },
            { name: '已恢复', value: s.recovered, itemStyle: { color: STATUS_COLOR[1] } },
            { name: '已确认', value: s.acked, itemStyle: { color: STATUS_COLOR[2] } },
          ],
        },
      ],
    })
  },
  { deep: true, flush: 'post' },
)

// ---------------- 电站选择与遥测 KPI（P1-4 储能遥测驾驶舱：影子实时快照聚合） ----------------

/** 电站下拉数据源 */
const stations = ref<Station[]>([])
/** 当前选中电站 id（stationId 为 Long，序列化为字符串） */
const selectedStation = ref('')
/** 选中电站下的设备（一次取回 200 条，覆盖单站设备规模） */
const stationDevices = ref<Device[]>([])
/** 遥测 KPI：功率为站内 PCS 求和，其余为均值；onlineDevices 为有属性上报的 PCS 台数 */
const telemetry = ref({ soc: 0, power: 0, voltage: 0, current: 0, temp: 0, onlineDevices: 0 })

/** 加载电站列表（现有 API 方法名为 stationPage） */
async function loadStations(): Promise<void> {
  try {
    const page = await stationApi.stationPage({ pageNum: 1, pageSize: 100 })
    stations.value = page.records
  } catch (e) {
    ElMessage.error(`电站列表加载失败：${e instanceof Error ? e.message : String(e)}`)
  }
}

/** 按选中电站拉取站下设备（后端 DeviceQuery 支持 stationId 过滤） */
async function loadStationDevices(): Promise<void> {
  if (!selectedStation.value) {
    stationDevices.value = []
    return
  }
  const page = await deviceApi.page({ pageNum: 1, pageSize: 200, stationId: selectedStation.value })
  stationDevices.value = page.records
}

/** 取影子 reported 中某键的数值；缺失或非数值按 0 计 */
function numOf(rep: Record<string, unknown>, key: string): number {
  const v = rep[key]
  return typeof v === 'number' && Number.isFinite(v) ? v : 0
}

/** 求均值：仅统计有有效数值上报的设备（避免缺失按 0 拉低均值） */
function avg(shadows: ShadowView[], key: string): number {
  const vals = shadows.map((s) => numOf(s.reported, key)).filter((n) => n !== 0)
  return vals.length ? vals.reduce((a, b) => a + b, 0) / vals.length : 0
}

/** 求和：站级功率 = 各 PCS 功率之和 */
function sum(shadows: ShadowView[], key: string): number {
  return shadows.reduce((acc, s) => acc + numOf(s.reported, key), 0)
}

/** 聚合遥测 KPI：取站下全部 PCS 影子（productKey 含 meter 判为电表，其余为 PCS） */
async function loadTelemetry(): Promise<void> {
  const pcs = stationDevices.value.filter((d) => !d.productKey?.includes('meter'))
  if (pcs.length === 0) {
    telemetry.value = { soc: 0, power: 0, voltage: 0, current: 0, temp: 0, onlineDevices: 0 }
    return
  }
  const shadows = await Promise.all(pcs.map((d) => shadowApi.getShadow(String(d.deviceId)).catch(() => null)))
  const reported = shadows.filter((s): s is ShadowView => !!s?.reported)
  telemetry.value = {
    soc: avg(reported, 'soc'),
    power: sum(reported, 'power'),
    voltage: avg(reported, 'voltage'),
    current: avg(reported, 'current'),
    temp: avg(reported, 'temp'),
    onlineDevices: reported.length,
  }
}

/** 近 24h 遥测曲线：取首台 PCS 设备的 soc/power/temp 属性历史（TSDB），双 y 轴绘制 */
const curveData = ref<{ times: string[]; soc: number[]; power: number[]; temp: number[] }>({ times: [], soc: [], power: [], temp: [] })

/** 加载近 24h 属性历史：首台非电表设备（PCS 判定与 Task 1 一致）；无 PCS 时曲线置空 */
async function loadCurve(): Promise<void> {
  const pcs = stationDevices.value.find((d) => !d.productKey?.includes('meter'))
  if (!pcs) {
    curveData.value = { times: [], soc: [], power: [], temp: [] }
    return
  }
  const end = Date.now()
  const start = end - 24 * 3600 * 1000
  const view = await tsdbApi.propertyHistory({
    deviceId: String(pcs.deviceId),
    productKey: pcs.productKey,
    identifiers: ['soc', 'power', 'temp'],
    startTime: start,
    endTime: end,
    order: 'asc',
    page: 1,
    size: 2000,
  })
  const times: string[] = []
  const soc: number[] = []
  const power: number[] = []
  const temp: number[] = []
  for (const r of view.records) {
    times.push(new Date(r.ts).toTimeString().slice(0, 5))
    soc.push(Number(r.values.soc ?? 0))
    power.push(Number(r.values.power ?? 0))
    temp.push(Number(r.values.temp ?? 0))
  }
  curveData.value = { times, soc, power, temp }
}

/** 遥测曲线容器（近 24h：功率/SOC/温度） */
const telemetryEl = ref<HTMLElement>()
const { render: renderTelemetry } = useEChart(telemetryEl)

/** 渲染近 24h 遥测曲线：左 y 轴功率、右 y 轴 SOC/温度（曲线色沿用项目色板语义色） */
function renderTelemetryChart(): void {
  const pcs = stationDevices.value.find((d) => !d.productKey?.includes('meter'))
  const name = pcs?.deviceName ? ` · ${pcs.deviceName}` : ''
  renderTelemetry({
    animation: false,
    title: { text: `近 24h 遥测${name}`, left: 'center', textStyle: { fontSize: 13, color: '#1F2833', fontWeight: 600 } },
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
}

/** 今日收益（电站级，DAY 周期） */
const revenue = ref<RevenueSummary | null>(null)

/** 加载电站当日收益汇总；未选电站时置空 */
async function loadRevenue(): Promise<void> {
  if (!selectedStation.value) {
    revenue.value = null
    return
  }
  revenue.value = await emsApi.revenueSummary({ stationId: selectedStation.value, periodType: 'DAY' })
}

/** 电站切换：先取站下设备，再聚合遥测、拉取曲线与当日收益 */
async function onStationChange(): Promise<void> {
  try {
    await loadStationDevices()
    await loadTelemetry()
    await loadCurve()
    renderTelemetryChart()
    await loadRevenue()
  } catch (e) {
    ElMessage.error(`遥测加载失败：${e instanceof Error ? e.message : String(e)}`)
  }
}

onMounted(() => {
  load()
  loadStations()
  renderTelemetryChart()
})
</script>

<template>
  <div class="ex-page">
    <el-alert v-if="error" type="error" :closable="false" show-icon class="err-alert">
      驾驶舱数据加载失败：{{ error }}。请确认网关（127.0.0.1:8000）与告警服务已启动。
    </el-alert>

    <header class="ex-page-head">
      <div class="head-title">
        <h1 class="ex-title">设备监控</h1>
        <p class="ex-sub">储能遥测驾驶舱 · 遥测为影子实时快照聚合，告警状态为精确计数、图表为近 500 条样本窗口</p>
      </div>
      <el-select v-model="selectedStation" placeholder="选择电站" filterable clearable style="width: 220px" @change="onStationChange">
        <el-option v-for="s in stations" :key="s.stationId" :label="s.stationName" :value="s.stationId" />
      </el-select>
    </header>

    <!-- 遥测读数带：PCS 影子实时快照聚合（SOC/功率/电压/电流/温度/在线设备） -->
    <section class="ex-readout-band" style="--ro-cols: 6" aria-label="储能遥测读数">
      <div class="ex-readout">
        <span class="ex-readout-label">SOC</span>
        <span class="ex-readout-value"><b class="ex-num">{{ telemetry.soc.toFixed(1) }}</b><em>%</em></span>
      </div>
      <div class="ex-readout">
        <span class="ex-readout-label">功率</span>
        <span class="ex-readout-value" :class="telemetry.power >= 0 ? 'charge' : 'discharge'"><b class="ex-num">{{ telemetry.power.toFixed(1) }}</b><em>kW</em></span>
      </div>
      <div class="ex-readout">
        <span class="ex-readout-label">电压</span>
        <span class="ex-readout-value"><b class="ex-num">{{ telemetry.voltage.toFixed(1) }}</b><em>V</em></span>
      </div>
      <div class="ex-readout">
        <span class="ex-readout-label">电流</span>
        <span class="ex-readout-value"><b class="ex-num">{{ telemetry.current.toFixed(1) }}</b><em>A</em></span>
      </div>
      <div class="ex-readout">
        <span class="ex-readout-label">温度</span>
        <span class="ex-readout-value"><b class="ex-num">{{ telemetry.temp.toFixed(1) }}</b><em>℃</em></span>
      </div>
      <div class="ex-readout">
        <span class="ex-readout-label">在线设备</span>
        <span class="ex-readout-value"><b class="ex-num">{{ telemetry.onlineDevices }}</b><em>台</em></span>
      </div>
    </section>

    <!-- 今日收益卡：电站级 DAY 周期收益汇总（套利收益/电量） -->
    <section class="ex-card revenue-card">
      <div class="ex-card-head">
        <h2 class="ex-card-title">今日收益（电站级）</h2>
      </div>
      <div class="ex-readout-band" style="--ro-cols: 4" aria-label="电站收益读数">
        <div class="ex-readout">
          <span class="ex-readout-label">套利收益</span>
          <span class="ex-readout-value charge"><b class="ex-num">{{ revenue ? revenue.arbitrageRevenue.toFixed(2) : '—' }}</b><em>元</em></span>
        </div>
        <div class="ex-readout">
          <span class="ex-readout-label">放电量</span>
          <span class="ex-readout-value discharge"><b class="ex-num">{{ revenue ? revenue.dischargeEnergy.toFixed(1) : '—' }}</b><em>kWh</em></span>
        </div>
        <div class="ex-readout">
          <span class="ex-readout-label">充电量</span>
          <span class="ex-readout-value charge"><b class="ex-num">{{ revenue ? revenue.chargeEnergy.toFixed(1) : '—' }}</b><em>kWh</em></span>
        </div>
        <div class="ex-readout">
          <span class="ex-readout-label">总电量</span>
          <span class="ex-readout-value"><b class="ex-num">{{ revenue ? revenue.totalEnergy.toFixed(1) : '—' }}</b><em>kWh</em></span>
        </div>
      </div>
    </section>

    <!-- 近 24h 遥测曲线：首台 PCS 的功率/SOC/温度属性历史（双 y 轴） -->
    <section class="ex-card chart-card">
      <div ref="telemetryEl" class="chart"></div>
    </section>

    <!-- 告警仪表读数带 -->
    <section class="ex-readout-band" style="--ro-cols: 4" aria-label="告警状态计数">
      <div class="ex-readout">
        <span class="ex-readout-label">触发中告警</span>
        <span class="ex-readout-value danger"><b>{{ stats.active }}</b></span>
      </div>
      <div class="ex-readout">
        <span class="ex-readout-label">已恢复告警</span>
        <span class="ex-readout-value charge"><b>{{ stats.recovered }}</b></span>
      </div>
      <div class="ex-readout">
        <span class="ex-readout-label">已确认告警</span>
        <span class="ex-readout-value"><b>{{ stats.acked }}</b></span>
      </div>
      <div class="ex-readout">
        <span class="ex-readout-label">样本涉及设备数</span>
        <span class="ex-readout-value discharge"><b>{{ summary.deviceCount }}</b><em>台</em></span>
      </div>
    </section>

    <!-- 图表区 -->
    <section class="chart-row">
      <div class="chart-card ex-card">
        <div ref="trendEl" class="chart"></div>
      </div>
      <div class="chart-card ex-card">
        <div ref="levelEl" class="chart"></div>
      </div>
      <div class="chart-card ex-card">
        <div ref="statusEl" class="chart"></div>
      </div>
    </section>

    <!-- 最近告警 -->
    <section class="ex-card list-card">
      <div class="ex-card-head">
        <h2 class="ex-card-title">最近告警（样本窗口）</h2>
      </div>
      <el-table :data="recentAlarms" size="small" empty-text="暂无告警数据" v-loading="loading">
        <el-table-column prop="ruleCode" label="规则" width="130" />
        <el-table-column label="级别" width="80">
          <template #default="{ row }"><AlarmLevelTag :level="row.level" /></template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="deviceId" label="设备ID" width="90" />
        <el-table-column prop="type" label="类型" width="70">
          <template #default="{ row }">{{ typeText(row.type) }}</template>
        </el-table-column>
        <el-table-column prop="message" label="内容" show-overflow-tooltip />
        <el-table-column label="触发时间" width="160">
          <template #default="{ row }"><span class="ex-num">{{ toLocal(row.triggeredTime) }}</span></template>
        </el-table-column>
      </el-table>
    </section>
  </div>
</template>

<style scoped>
.err-alert {
  margin-bottom: 0;
}
.chart-row {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 14px;
}
.chart-card {
  padding: 16px 14px 8px;
}
.revenue-card {
  padding: 0 18px 18px;
}
.chart {
  height: 260px;
  width: 100%;
}
.list-card {
  padding-bottom: 8px;
}
@media (max-width: 900px) {
  .chart-row {
    grid-template-columns: 1fr;
  }
}
</style>
