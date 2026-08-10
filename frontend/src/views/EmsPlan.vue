<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { emsApi } from '@/api/ems'
import { useEChart } from '@/composables/useEChart'
import type { EmsElectricityPrice, EmsPlan, EmsPlanPoint, EmsStrategy, Station } from '@/types/models'
import { loadStations, stationName } from '@/utils/stationDict'
import { constraintReady } from '@/utils/planGate'

const router = useRouter()

const list = ref<EmsPlan[]>([])
const total = ref(0)
const pageNo = ref(1)
const pageSize = ref(10)
const selected = ref<EmsPlan>()
const points = ref<EmsPlanPoint[]>([])
const chartEl = ref<HTMLElement>()
const { chart, render } = useEChart(chartEl)

/** 名称化：电站/策略 id → 名称映射（查不到回退裸 id，见 Global Constraints 3） */
const stations = ref<Station[]>([])
const strategies = ref<EmsStrategy[]>([])
function strategyLabel(id: string | undefined): string {
  return strategies.value.find((s) => String(s.strategyId) === String(id))?.strategyName ?? String(id ?? '')
}

/** 语义色：充电/放电/待机。波形柱按动作分色，仪器读数带用同一套 token。 */
const ACTION_COLOR: Record<string, string> = {
  CHARGE: '#2E9E5B',
  DISCHARGE: '#E08A1E',
  STANDBY: '#94A0AC',
}
/** 峰/平/谷电价时段淡色底纹（DEEP/PEEK 为 V1 物模型拼写，一并兜底） */
const PRICE_TINT: Record<string, string> = {
  DEEP: 'rgba(46,158,91,0.12)',
  VALLEY: 'rgba(46,158,91,0.07)',
  FLAT: 'rgba(148,160,172,0.08)',
  PEAK: 'rgba(224,138,30,0.10)',
  PEEK: 'rgba(224,138,30,0.10)',
}

const STATUS_TEXT: Record<number, string> = { 0: '待执行', 1: '执行中', 2: '完成', 3: '已取消' }
const STATUS_TAG: Record<number, 'success' | 'primary' | 'info'> = { 0: 'info', 1: 'primary', 2: 'success', 3: 'info' }

const statusText = computed(() => STATUS_TEXT[selected.value?.status ?? 0])
/** 仪表读数：各动作累计电量 = Σ 功率 × 30 分钟槽 */
const chargeKwh = computed(() => round1(sumPower('CHARGE') * 0.5))
const dischargeKwh = computed(() => round1(sumPower('DISCHARGE') * 0.5))
const pointCount = computed(() => points.value.length)
/** 0 点计划：所选策略类型后端不支持生成 → 渲染空态而非空白波形 */
const emptyPoints = computed(() => points.value.length === 0)
const endSoc = computed(() => {
  const last = points.value.length ? points.value[points.value.length - 1] : undefined
  return last ? Math.round(last.socTarget) : 0
})

function round1(n: number): number {
  return Math.round(n * 10) / 10
}
function sumPower(action: string): number {
  return points.value.filter((p) => p.action === action).reduce((s, p) => s + p.powerKw, 0)
}

function toMinutes(t: string): number {
  const m = t.match(/^(\d{1,2}):(\d{2})/)
  return m ? Number(m[1]) * 60 + Number(m[2]) : NaN
}
function timeLabel(t: string): string {
  return t.length >= 5 ? t.slice(0, 5) : t
}

async function load(): Promise<void> {
  try {
    const data = await emsApi.planPage({ pageNo: pageNo.value, pageSize: pageSize.value })
    list.value = data.records
    total.value = data.total
    if (list.value.length) {
      await selectPlan(list.value[0]) // 列表按计划日期倒序，默认展示最近一条
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  }
}

async function loadMaps() {
  try {
    const [stationList, strategyPage] = await Promise.all([
      loadStations(),
      emsApi.strategyPage({ pageNo: 1, pageSize: 100 }),
    ])
    stations.value = stationList
    strategies.value = strategyPage.records
  } catch {
    // 名称回退裸 id，不阻断页面
  }
}

async function selectPlan(plan: EmsPlan): Promise<void> {
  selected.value = plan
  try {
    const pts = await emsApi.planPoints(plan.planId)
    points.value = pts
    if (!pts.length) {
      chart.value?.clear() // 清掉上一条计划的残留波形（若有）
      return
    }
    const bands = await fetchBands(plan.stationId, plan.planDate)
    renderChart(pts, bands)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  }
}

/** 电价时段：拉取该站全量分时电价，过滤在计划日期有效期的档位 */
async function fetchBands(stationId: string, planDate: string): Promise<EmsElectricityPrice[]> {
  try {
    const page = await emsApi.pricePage({ pageNo: 1, pageSize: 100, stationId })
    const day = planDate.slice(0, 10)
    return page.records.filter((p) => {
      if (p.status === 0) return false
      if (p.validFrom && day < p.validFrom.slice(0, 10)) return false
      if (p.validTo && day > p.validTo.slice(0, 10)) return false
      return true
    })
  } catch {
    return [] // 无价格数据时波形照常渲染，仅缺底纹
  }
}

/** 时段带 → ECharts markArea 数据。用分钟数对齐到 30 分钟点序列，避免 HH:mm / HH:mm:ss 格式差异。 */
interface BandMark {
  xAxis: string
  itemStyle?: { color: string }
}
function buildBands(bands: EmsElectricityPrice[], times: string[]): [BandMark, BandMark][] {
  const mins = times.map((t) => toMinutes(t))
  const out: [BandMark, BandMark][] = []
  for (const b of bands) {
    const s = toMinutes(timeLabel(b.startTime))
    const e = toMinutes(timeLabel(b.endTime))
    if (Number.isNaN(s) || Number.isNaN(e)) continue
    const startIdx = mins.findIndex((m) => m >= s)
    if (startIdx === -1) continue
    let endIdx = -1
    for (let i = 0; i < mins.length; i++) if (mins[i] < e) endIdx = i
    if (endIdx < startIdx) continue
    out.push([
      { xAxis: times[startIdx], itemStyle: { color: PRICE_TINT[b.priceType] ?? PRICE_TINT.FLAT } },
      { xAxis: times[endIdx] },
    ])
  }
  return out
}

function renderChart(pts: EmsPlanPoint[], bands: EmsElectricityPrice[]): void {
  const times = pts.map((p) => timeLabel(p.time))
  render({
    animation: false, // 数据仪表：无多余动效
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'line' },
      formatter(params: unknown) {
        const list = params as Array<{ axisValueLabel: string; seriesName: string; value: number | [number, number] }>
        const head = list[0]?.axisValueLabel ?? ''
        const rows = list.map((p) => {
          const v = typeof p.value === 'number' ? p.value : (p.value as [number, number] | undefined)?.[1] ?? 0
          return p.seriesName === 'SOC' ? `SOC：${round1(v)} %` : `${p.seriesName}：${round1(v)} kW`
        })
        return [head, ...rows].join('<br/>')
      },
    },
    grid: { left: 56, right: 58, top: 48, bottom: 40 },
    xAxis: {
      type: 'category',
      data: times,
      boundaryGap: true,
      axisLine: { lineStyle: { color: '#DCE2EA' } },
      axisTick: { show: false },
      axisLabel: { color: '#5B6B8C', fontFamily: 'Cascadia Mono, Consolas, monospace' },
    },
    yAxis: [
      {
        type: 'value',
        name: '功率 kW',
        nameTextStyle: { color: '#5B6B8C' },
        axisLabel: { color: '#5B6B8C', fontFamily: 'Cascadia Mono, Consolas, monospace' },
        splitLine: { lineStyle: { color: '#ECF0F5' } },
      },
      {
        type: 'value',
        name: 'SOC %',
        min: 0,
        max: 100,
        nameTextStyle: { color: '#5B6B8C' },
        axisLabel: { color: '#5B6B8C', fontFamily: 'Cascadia Mono, Consolas, monospace' },
        splitLine: { show: false },
      },
    ],
    series: [
      {
        name: '功率',
        type: 'bar',
        barWidth: '62%',
        data: pts.map((p) => ({ value: p.powerKw, itemStyle: { color: ACTION_COLOR[p.action] ?? ACTION_COLOR.STANDBY } })),
        markArea: { silent: true, data: buildBands(bands, times) },
      },
      {
        name: 'SOC',
        type: 'line',
        yAxisIndex: 1,
        data: pts.map((p) => p.socTarget),
        smooth: true,
        symbol: 'none',
        lineStyle: { color: '#1F2833', width: 1.5 },
      },
    ],
  })
}

async function dispatchSelected(): Promise<void> {
  if (!selected.value) return
  await dispatch(selected.value)
}

async function dispatch(row: EmsPlan): Promise<void> {
  try {
    await emsApi.dispatch(row.planId)
    ElMessage.success('已下发')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  }
}

// ---- 内联「生成计划」弹窗 ----
const genDialogVisible = ref(false)
const genErrs = ref<Record<string, string>>({})
const generating = ref(false)
const genForm = ref<{ planDate: string; stationId?: string; strategyId?: string }>({ planDate: todayStr() })
const genStrategies = ref<EmsStrategy[]>([])
watch(() => [genForm.value.planDate, genForm.value.stationId], () => { genErrs.value = {} })

function todayStr(): string {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

function openGenerate() {
  genForm.value = { planDate: todayStr() }
  genStrategies.value = []
  genDialogVisible.value = true
}

/** 电站变化 → 拉该站启用策略候选（status=1），并清空已选策略 */
async function onGenStationChange(stationId?: string) {
  genForm.value.strategyId = undefined
  genStrategies.value = []
  if (!stationId) return
  try {
    const page = await emsApi.strategyPage({ pageNo: 1, pageSize: 50, stationId, status: 1 })
    if (genForm.value.stationId !== stationId) return // 过期响应丢弃：候选串站会把 A 站策略带到 B 站生成错误计划
    genStrategies.value = page.records
  } catch {
    if (genForm.value.stationId === stationId) genStrategies.value = [] // 候选拉取失败：策略留空走自动选择
  }
}

async function generate() {
  genErrs.value = {}
  const e: Record<string, string> = {}
  const { planDate, stationId, strategyId } = genForm.value
  if (!planDate) e.planDate = '请选择计划日期'
  if (!stationId) e.stationId = '请选择电站'
  if (Object.keys(e).length) { genErrs.value = e; return }
  if (!(await constraintReady(stationId!))) {
    try {
      await ElMessageBox.confirm(
        `该电站「${stationName(stationId!, stations.value)}」未配置安全约束（或 SOC/功率上下限缺失），无法生成计划。是否前往安全约束页面配置？`,
        '安全约束未就绪',
        { confirmButtonText: '去配置', cancelButtonText: '取消', type: 'warning' },
      )
      void router.push('/ems/constraint')
    } catch {
      // 用户取消跳转
    }
    return
  }
  generating.value = true
  try {
    await emsApi.planGenerate({ stationId: stationId!, strategyId: strategyId || undefined, planDate })
    ElMessage.success('计划已生成')
    genDialogVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    generating.value = false
  }
}

function onRowClick(row: EmsPlan): void {
  void selectPlan(row)
}

onMounted(() => {
  void load()
  void loadMaps()
})
</script>

<template>
  <div class="plan-page">
    <header class="page-head">
      <div class="head-title">
        <h1 class="page-title">充放电计划</h1>
        <p class="page-sub">
          {{ selected ? `${selected.planDate} · 电站 ${stationName(selected.stationId, stations)} · 策略 ${strategyLabel(selected.strategyId)}` : '加载中…' }}
        </p>
      </div>
      <el-button class="gen-btn" type="success" :disabled="generating" @click="openGenerate">生成计划</el-button>
      <el-button
        class="dispatch-btn"
        type="primary"
        :disabled="!selected || selected.status !== 0"
        @click="dispatchSelected"
      >
        下发计划
      </el-button>
    </header>

    <section class="readout-band" aria-label="当日计划汇总">
      <div class="readout">
        <span class="readout-label">累计充电</span>
        <span class="readout-value"><b>{{ chargeKwh }}</b><em>kWh</em></span>
      </div>
      <div class="readout">
        <span class="readout-label">累计放电</span>
        <span class="readout-value"><b>{{ dischargeKwh }}</b><em>kWh</em></span>
      </div>
      <div class="readout">
        <span class="readout-label">计划点数</span>
        <span class="readout-value"><b>{{ pointCount }}</b><em>点</em></span>
      </div>
      <div class="readout">
        <span class="readout-label">计划末 SOC</span>
        <span class="readout-value"><b>{{ endSoc }}</b><em>%</em></span>
      </div>
      <div class="readout readout-status">
        <span class="readout-label">状态</span>
        <el-tag :type="STATUS_TAG[selected?.status ?? 0]" effect="light" round>{{ statusText }}</el-tag>
      </div>
    </section>

    <section class="wave-card">
      <div class="wave-head">
        <h2 class="wave-title">今日充放电波形</h2>
        <ul class="legend">
          <li><i class="sw sw-charge" aria-hidden="true"></i>充电</li>
          <li><i class="sw sw-discharge" aria-hidden="true"></i>放电</li>
          <li><i class="sw sw-standby" aria-hidden="true"></i>待机</li>
          <li class="sep" aria-hidden="true">·</li>
          <li><i class="sw sw-peak" aria-hidden="true"></i>峰</li>
          <li><i class="sw sw-flat" aria-hidden="true"></i>平</li>
          <li><i class="sw sw-valley" aria-hidden="true"></i>谷</li>
        </ul>
      </div>
      <div v-if="!emptyPoints" ref="chartEl" class="wave" role="img" aria-label="充放电功率柱状与 SOC 目标曲线，底纹为分时电价时段"></div>
      <el-empty v-else description="该计划无点序列——所选策略类型暂不支持生成" :image-size="96" class="wave-empty" />
      <p class="wave-note">底纹为分时电价时段（低谷充电、高峰放电的套利逻辑一眼可读）；SOC 线为计划目标荷电状态。</p>
    </section>

    <section class="list-card">
      <el-table :data="list" class="plan-table" highlight-current-row @row-click="onRowClick">
        <el-table-column prop="planDate" label="计划日期" min-width="110" />
        <el-table-column label="电站" min-width="120">
          <template #default="{ row }">{{ stationName(row.stationId, stations) }}</template>
        </el-table-column>
        <el-table-column label="策略" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ strategyLabel(row.strategyId) }}</template>
        </el-table-column>
        <el-table-column prop="totalEnergy" label="总量 kWh" width="110" align="right">
          <template #default="{ row }">{{ row.totalEnergy ?? '—' }}</template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="STATUS_TAG[row.status as number]" effect="light" round>{{ STATUS_TEXT[row.status as number] }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140" align="right">
          <template #default="{ row }">
            <el-button size="small" @click.stop="onRowClick(row)">查看</el-button>
            <el-button size="small" type="success" :disabled="row.status !== 0" @click.stop="dispatch(row)">下发</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-model:current-page="pageNo"
        v-model:page-size="pageSize"
        :total="total"
        @change="load"
        layout="total, prev, pager, next"
      />
    </section>

    <!-- 生成调度计划弹窗 -->
    <el-dialog v-model="genDialogVisible" title="生成调度计划" width="480px">
      <el-form label-width="80px">
        <el-form-item label="计划日期" required :error="genErrs.planDate">
          <el-date-picker v-model="genForm.planDate" type="date" value-format="YYYY-MM-DD" placeholder="选择日期" style="width: 100%" />
        </el-form-item>
        <el-form-item label="电站" required :error="genErrs.stationId">
          <el-select v-model="genForm.stationId" placeholder="请选择电站" filterable clearable style="width: 100%" @change="onGenStationChange">
            <el-option v-for="s in stations" :key="s.stationId" :label="s.stationName" :value="s.stationId" />
          </el-select>
        </el-form-item>
        <el-form-item label="策略">
          <el-select v-model="genForm.strategyId" :placeholder="genForm.stationId ? '自动选择（启用中优先级最高）' : '请先选择电站'" clearable :disabled="!genForm.stationId" style="width: 100%">
            <el-option v-for="s in genStrategies" :key="s.strategyId" :label="s.strategyName" :value="s.strategyId" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="genDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="generating" @click="generate">生成</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.plan-page {
  display: flex;
  flex-direction: column;
  gap: 14px;
  max-width: 1200px;
  margin: 0 auto;
  color: #1f2833;
}
.page-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.page-title {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
  letter-spacing: 0.5px;
  color: #1f2833;
}
.page-sub {
  margin: 4px 0 0;
  font-size: 13px;
  color: #5b6b8c;
  font-variant-numeric: tabular-nums;
}
.dispatch-btn {
  min-width: 112px;
}
/* 仪表读数带：白卡 + 发丝分隔，数字用 Bahnschrift（DIN 工业字形） */
.readout-band {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  background: #fff;
  border: 1px solid #dce2ea;
  border-radius: 6px;
  overflow: hidden;
}
.readout {
  padding: 14px 18px;
  border-left: 1px solid #eef1f6;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.readout:first-child {
  border-left: none;
}
.readout-label {
  font-size: 12px;
  color: #5b6b8c;
  letter-spacing: 1px;
}
.readout-value {
  font-family: 'Bahnschrift', 'DIN Alternate', 'Segoe UI', sans-serif;
  font-weight: 600;
  font-size: 26px;
  line-height: 1;
  color: #1f2833;
  font-variant-numeric: tabular-nums;
  display: flex;
  align-items: baseline;
  gap: 6px;
}
.readout-value em {
  font-style: normal;
  font-size: 13px;
  color: #5b6b8c;
  font-weight: 400;
}
.readout-status {
  justify-content: center;
}
.wave-card,
.list-card {
  background: #fff;
  border: 1px solid #dce2ea;
  border-radius: 6px;
}
.wave-card {
  padding: 18px 20px 14px;
}
.wave-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 10px;
}
.wave-title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: #1f2833;
}
.legend {
  display: flex;
  align-items: center;
  gap: 14px;
  margin: 0;
  padding: 0;
  list-style: none;
  font-size: 12px;
  color: #5b6b8c;
}
.legend li {
  display: flex;
  align-items: center;
  gap: 6px;
}
.legend .sep {
  color: #c6cfdb;
}
.sw {
  width: 12px;
  height: 12px;
  border-radius: 2px;
  display: inline-block;
}
.sw-charge {
  background: #2e9e5b;
}
.sw-discharge {
  background: #e08a1e;
}
.sw-standby {
  background: #94a0ac;
}
.sw-peak {
  background: rgba(224, 138, 30, 0.18);
  border: 1px solid rgba(224, 138, 30, 0.5);
}
.sw-flat {
  background: rgba(148, 160, 172, 0.16);
  border: 1px solid rgba(148, 160, 172, 0.45);
}
.sw-valley {
  background: rgba(46, 158, 91, 0.14);
  border: 1px solid rgba(46, 158, 91, 0.4);
}
.wave {
  height: 340px;
  width: 100%;
}
.wave-empty {
  height: 340px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
}
.wave-note {
  margin: 8px 0 0;
  font-size: 12px;
  color: #94a0ac;
}
.list-card {
  padding: 6px 14px 14px;
}
.plan-table {
  width: 100%;
}
@media (max-width: 900px) {
  .readout-band {
    grid-template-columns: repeat(2, 1fr);
  }
  .readout:nth-child(3) {
    border-left: none;
  }
  .readout {
    border-top: 1px solid #eef1f6;
  }
  .readout:nth-child(-n + 2) {
    border-top: none;
  }
}
</style>
