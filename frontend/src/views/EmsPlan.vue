<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deviceApi } from '@/api/device'
import { emsApi } from '@/api/ems'
import { useEChart } from '@/composables/useEChart'
import type { EmsElectricityPrice, EmsExecutionRecord, EmsPlan, EmsPlanPoint, EmsStrategy, Station } from '@/types/models'
import { loadStations, stationName } from '@/utils/stationDict'
import { constraintReady } from '@/utils/planGate'
import { fetchActualCurve } from '@/utils/planCurve'

const router = useRouter()

/** 列表筛选：电站下拉后端已支持 stationId；状态下拉为 UI 占位，等 Task 3 后端支持 status 后再启用 */
const filters = ref<{ stationId?: string; status?: number }>({})
/** 右主区 Tab：wave=计划波形 / exec=执行记录 */
const activeTab = ref('wave')

const list = ref<EmsPlan[]>([])
const total = ref(0)
const pageNo = ref(1)
const pageSize = ref(10)
const selected = ref<EmsPlan>()
const points = ref<EmsPlanPoint[]>([])
const execRecords = ref<EmsExecutionRecord[]>([])
const chartEl = ref<HTMLElement>()
const { chart, render } = useEChart(chartEl)

/** 实际功率曲线（TSDB 采样点）；未匹配下发设备或拉取失败为 null，波形正常渲染但不叠加实际系列 */
const actualCurve = ref<{ times: string[]; power: number[] } | null>(null)
/** 下发设备名：与后端 energyx.ems.device-name 对齐（缺省 ess-dev-01），按 deviceName 反查设备 */
const PCS_DEVICE_NAME = 'ess-dev-01'

/** 名称化：电站/策略 id → 名称映射（查不到回退裸 id） */
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

const STATUS_TEXT: Record<number, string> = { 0: '待执行', 1: '执行中', 2: '完成', 3: '已取消', 4: '失败' }
const STATUS_TAG: Record<number, 'success' | 'primary' | 'info' | 'danger'> = { 0: 'info', 1: 'primary', 2: 'success', 3: 'info', 4: 'danger' }

/** 计划点执行状态语义（执行记录表格用） */
const EXEC_STATE_TEXT: Record<number, string> = { 0: '待下发', 1: '已下发', 2: '成功', 3: '失败', 4: '超时' }
const EXEC_STATE_TAG: Record<number, 'info' | 'primary' | 'success' | 'danger' | 'warning'> = {
  0: 'info',
  1: 'primary',
  2: 'success',
  3: 'danger',
  4: 'warning',
}

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
    const data = await emsApi.planPage({
      pageNo: pageNo.value,
      pageSize: pageSize.value,
      // 电站筛选后端已支持，stationId 为空时后端忽略；status 待 Task 3 支持，此处不传
      stationId: filters.value.stationId || undefined,
    })
    list.value = data.records
    total.value = data.total
    if (list.value.length) {
      // 列表按计划日期倒序，默认展示最近一条
      await selectPlan(list.value[0])
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  }
}

/** 筛选条件变化：回到第一页重新查询，避免停在超出筛选结果范围的页码 */
function onFilterChange(): void {
  pageNo.value = 1
  void load()
}

/** 行高亮：el-table 的 current-row 会随 data 替换丢失，用选中态补画，保持视觉连续 */
function rowClassName({ row }: { row: EmsPlan }): string {
  return selected.value?.planId === row.planId ? 'ex-row-current' : ''
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
      // 清掉上一条计划的残留波形（若有）
      chart.value?.clear()
      return
    }
    const bands = await fetchBands(plan.stationId, plan.planDate)
    renderChart(pts, bands)
    // 实际曲线独立异步拉取，拉回后重渲染叠加虚线；失败已在 loadActualCurve 内降级为 null
    await loadActualCurve(plan)
    renderChart(pts, bands, actualCurve.value)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  }
}

/** 反查下发设备（energyx.ems.device-name 对应设备）拿 deviceId + productKey，拉当日实际功率曲线 */
async function loadActualCurve(plan: EmsPlan): Promise<void> {
  actualCurve.value = null
  try {
    const page = await deviceApi.page({ pageNum: 1, pageSize: 20 })
    const dev = page.records.find((d) => d.deviceName === PCS_DEVICE_NAME)
    if (!dev) {
      // 设备列表未匹配到下发设备：不展示实际曲线，波形照常渲染
      return
    }
    actualCurve.value = await fetchActualCurve(String(dev.deviceId), dev.productKey, plan.planDate.slice(0, 10))
  } catch {
    // 拉取失败降级：无实际曲线不阻断波形
    actualCurve.value = null
  }
}

/** 加载选中计划的执行记录（点级下发/ACK 结果）；失败静默不影响波形展示 */
async function loadExecRecords(): Promise<void> {
  if (!selected.value) {
    execRecords.value = []
    return
  }
  try {
    execRecords.value = await emsApi.planRecords(selected.value.planId)
  } catch {
    execRecords.value = []
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
    // 无价格数据时波形照常渲染，仅缺底纹
    return []
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

function renderChart(pts: EmsPlanPoint[], bands: EmsElectricityPrice[], actual?: { times: string[]; power: number[] } | null): void {
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
      // 实际功率：TSDB 采样点按索引对齐计划 category 轴（密度不同近似展示），浅蓝虚线
      ...(actual && actual.power.length
        ? [{
            name: '实际功率（采样点）',
            type: 'line' as const,
            yAxisIndex: 0,
            data: actual.power,
            smooth: true,
            symbol: 'none',
            lineStyle: { color: '#2B6CB0', width: 1.2, type: 'dashed' as const },
          }]
        : []),
    ],
  })
}

async function dispatchSelected(): Promise<void> {
  if (!selected.value) return
  await dispatch(selected.value)
}

async function dispatch(row: EmsPlan): Promise<void> {
  try {
    const sent = await emsApi.dispatch(row.planId)
    ElMessage.success(`已受理下发，立即下发 ${sent} 点，其余到点自动执行`)
    await load()
    await loadExecRecords()
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
    // 过期响应丢弃：候选串站会把 A 站策略带到 B 站生成错误计划
    if (genForm.value.stationId !== stationId) return
    genStrategies.value = page.records
  } catch {
    // 候选拉取失败：策略留空走自动选择
    if (genForm.value.stationId === stationId) genStrategies.value = []
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

/** 选中计划变化 → 联动加载执行记录 */
watch(selected, () => void loadExecRecords())

onMounted(() => {
  void load()
  void loadMaps()
})
</script>

<template>
  <div class="plan-page">
    <div class="plan-layout">
      <!-- 左栏：计划列表（电站/状态筛选 + 表格 + 分页） -->
      <aside class="plan-list ex-card">
        <div class="filter-bar">
          <el-select v-model="filters.stationId" placeholder="全部电站" clearable filterable @change="onFilterChange">
            <el-option v-for="s in stations" :key="s.stationId" :label="s.stationName" :value="s.stationId" />
          </el-select>
          <!-- 状态筛选：后端 planPage 暂不支持 status，占位禁用，Task 3 支持后启用 -->
          <el-select v-model="filters.status" placeholder="状态筛选（待支持）" clearable disabled>
            <el-option v-for="(text, code) in STATUS_TEXT" :key="code" :label="text" :value="Number(code)" />
          </el-select>
        </div>

        <el-table
          :data="list"
          class="plan-table"
          size="small"
          highlight-current-row
          :row-class-name="rowClassName"
          @row-click="onRowClick"
        >
          <el-table-column prop="planDate" label="日期" width="104" />
          <el-table-column label="电站" min-width="96" show-overflow-tooltip>
            <template #default="{ row }">{{ stationName(row.stationId, stations) }}</template>
          </el-table-column>
          <el-table-column label="策略" min-width="104" show-overflow-tooltip>
            <template #default="{ row }">{{ strategyLabel(row.strategyId) }}</template>
          </el-table-column>
          <el-table-column label="总量 kWh" width="92" align="right">
            <template #default="{ row }">{{ row.totalEnergy ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="76">
            <template #default="{ row }">
              <el-tag :type="STATUS_TAG[row.status as number]" effect="light" round size="small">{{ STATUS_TEXT[row.status as number] }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="132" align="right" fixed="right">
            <template #default="{ row }">
              <el-button size="small" @click.stop="onRowClick(row)">查看</el-button>
              <el-button size="small" type="success" :disabled="row.status !== 0" @click.stop="dispatch(row)">下发</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="pager">
          <el-pagination
            v-model:current-page="pageNo"
            v-model:page-size="pageSize"
            :total="total"
            layout="total, prev, pager, next"
            size="small"
            @change="load"
          />
        </div>
      </aside>

      <!-- 右栏：页头 + Tab 分栏（计划波形 / 执行记录） -->
      <main class="plan-main ex-card">
        <header class="plan-head ex-page-head">
          <div class="head-title">
            <h1 class="ex-title">充放电计划</h1>
            <p class="ex-sub">
              {{ selected ? `${stationName(selected.stationId, stations)} · ${strategyLabel(selected.strategyId)} · ${selected.planDate}` : '加载中…' }}
            </p>
          </div>
          <div class="head-actions">
            <el-button class="gen-btn" type="success" :disabled="generating" @click="openGenerate">生成计划</el-button>
            <el-button class="dispatch-btn" type="primary" :disabled="!selected || selected.status !== 0" @click="dispatchSelected">
              下发计划
            </el-button>
          </div>
        </header>

        <el-tabs v-model="activeTab" class="plan-tabs">
          <el-tab-pane label="计划波形" name="wave">
            <section class="ex-readout-band" aria-label="当日计划汇总">
              <div class="ex-readout">
                <span class="ex-readout-label">累计充电</span>
                <span class="ex-readout-value"><b>{{ chargeKwh }}</b><em>kWh</em></span>
              </div>
              <div class="ex-readout">
                <span class="ex-readout-label">累计放电</span>
                <span class="ex-readout-value"><b>{{ dischargeKwh }}</b><em>kWh</em></span>
              </div>
              <div class="ex-readout">
                <span class="ex-readout-label">计划点数</span>
                <span class="ex-readout-value"><b>{{ pointCount }}</b><em>点</em></span>
              </div>
              <div class="ex-readout">
                <span class="ex-readout-label">计划末 SOC</span>
                <span class="ex-readout-value"><b>{{ endSoc }}</b><em>%</em></span>
              </div>
              <div class="ex-readout ex-readout-status">
                <span class="ex-readout-label">状态</span>
                <el-tag :type="STATUS_TAG[selected?.status ?? 0]" effect="light" round>{{ statusText }}</el-tag>
              </div>
            </section>

            <div class="wave-head">
              <ul class="legend">
                <li><i class="sw sw-charge" aria-hidden="true"></i>充电</li>
                <li><i class="sw sw-discharge" aria-hidden="true"></i>放电</li>
                <li><i class="sw sw-standby" aria-hidden="true"></i>待机</li>
                <li class="sep" aria-hidden="true">·</li>
                <li><i class="sw sw-peak" aria-hidden="true"></i>峰</li>
                <li><i class="sw sw-flat" aria-hidden="true"></i>平</li>
                <li><i class="sw sw-valley" aria-hidden="true"></i>谷</li>
                <li class="sep" aria-hidden="true">·</li>
                <li v-if="actualCurve && actualCurve.power.length"><i class="sw sw-actual" aria-hidden="true"></i>实际</li>
                <li v-else class="legend-muted">实际（无数据）</li>
              </ul>
            </div>
            <div v-if="!emptyPoints" ref="chartEl" class="wave" role="img" aria-label="充放电功率柱状与 SOC 目标曲线，底纹为分时电价时段，浅蓝虚线为实际功率"></div>
            <el-empty v-else description="该计划无点序列——所选策略类型暂不支持生成" :image-size="96" class="wave-empty" />
            <p class="wave-note">底纹为分时电价时段（低谷充电、高峰放电的套利逻辑一眼可读）；SOC 线为计划目标荷电状态。</p>
          </el-tab-pane>

          <el-tab-pane label="执行记录" name="exec">
            <div class="exec-head">
              <h2 class="exec-title">执行记录</h2>
              <span class="exec-sub">下发后由调度器到点执行，ACK 回写点级结果；选中计划自动刷新</span>
            </div>
            <el-table :data="execRecords" empty-text="暂无执行记录——下发后调度器按计划点时刻到点下发" size="small" max-height="260">
              <el-table-column prop="planTime" label="计划时刻" width="100" align="center">
                <template #default="{ row }"><span class="mono-num">{{ row.planTime }}</span></template>
              </el-table-column>
              <el-table-column prop="action" label="动作" width="100" align="center">
                <template #default="{ row }">
                  <el-tag :type="row.action === 'CHARGE' ? 'success' : row.action === 'DISCHARGE' ? 'warning' : 'info'" size="small">
                    {{ row.action === 'CHARGE' ? '充电' : row.action === 'DISCHARGE' ? '放电' : '待机' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="state" label="状态" width="90" align="center">
                <template #default="{ row }">
                  <el-tag :type="EXEC_STATE_TAG[row.state as number] ?? 'info'" size="small">{{ EXEC_STATE_TEXT[row.state as number] }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="commandId" label="指令 ID" min-width="180" show-overflow-tooltip>
                <template #default="{ row }"><span class="mono-num">{{ row.commandId || '—' }}</span></template>
              </el-table-column>
              <el-table-column prop="result" label="回执" min-width="200" show-overflow-tooltip>
                <template #default="{ row }"><span class="mono-result">{{ row.result || '—' }}</span></template>
              </el-table-column>
            </el-table>
          </el-tab-pane>
        </el-tabs>
      </main>
    </div>

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
/* 页面整体：flex 上下堆叠改左右分栏，token 全部走 --ex-* */
.plan-page {
  display: flex;
  flex-direction: column;
  gap: 14px;
  max-width: 1240px;
  margin: 0 auto;
  color: var(--ex-ink);
}
.plan-layout {
  display: flex;
  align-items: flex-start;
  gap: 14px;
}
/* 左栏固定 360px，右主区吃掉剩余宽度 */
.plan-list {
  flex: 0 0 360px;
  width: 360px;
  padding: 12px 12px 14px;
}
.plan-main {
  flex: 1 1 auto;
  min-width: 0;
  padding: 16px 18px 12px;
}
/* 筛选条：电站 + 状态下拉各占一半 */
.filter-bar {
  display: flex;
  gap: 8px;
  margin-bottom: 10px;
}
.filter-bar .el-select {
  flex: 1;
  min-width: 0;
}
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 10px;
}
.plan-table {
  width: 100%;
}
/* 列表选中行：data 刷新后 current-row 丢失，用 rowClassName 补画底色 */
.plan-table :deep(tr.ex-row-current) {
  background: var(--ex-hair-soft);
}
.head-actions {
  display: flex;
  gap: 8px;
}
.plan-tabs {
  margin-top: 4px;
}
.plan-tabs :deep(.el-tabs__content) {
  padding: 14px 0 0;
}
.ex-readout-status {
  justify-content: center;
}
/* 波形：标题/图例行 + 图表 + 说明 */
.wave-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin: 14px 0 10px;
}
.legend {
  display: flex;
  align-items: center;
  gap: 14px;
  margin: 0;
  padding: 0;
  list-style: none;
  font-size: 12px;
  color: var(--ex-ink-2);
}
.legend li {
  display: flex;
  align-items: center;
  gap: 6px;
}
.legend .sep {
  color: var(--ex-ink-3);
}
.sw {
  width: 12px;
  height: 12px;
  border-radius: 2px;
  display: inline-block;
}
.sw-charge {
  background: var(--ex-charge);
}
.sw-discharge {
  background: var(--ex-discharge);
}
.sw-standby {
  background: var(--ex-ink-3);
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
/* 实际功率：虚线描边小方块，与波形上的浅蓝虚线呼应 */
.sw-actual {
  background: transparent;
  border: 1px dashed #2B6CB0;
}
/* 实际曲线无数据：图例灰置，表明已尝试拉取但不可用 */
.legend-muted {
  color: var(--ex-ink-3);
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
  color: var(--ex-ink-3);
}
/* 执行记录：头行 + 表格 */
.exec-head {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 8px;
}
.exec-title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--ex-ink);
}
.exec-sub {
  font-size: 12px;
  color: var(--ex-ink-3);
}
.mono-num {
  font-family: 'Cascadia Mono', Consolas, monospace;
  font-size: 12px;
  color: var(--ex-ink-2);
}
.mono-result {
  font-family: 'Cascadia Mono', Consolas, monospace;
  font-size: 12px;
  color: var(--ex-ink-2);
  word-break: break-all;
}
/* 小屏：左右分栏回退为上下堆叠 */
@media (max-width: 960px) {
  .plan-layout {
    flex-direction: column;
  }
  .plan-list {
    flex: none;
    width: 100%;
  }
}
</style>
