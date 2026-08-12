<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { emsApi } from '@/api/ems'
import type { EmsStationMeta, RevenueDetailRow, RevenueSummary, RevenueTrendPoint, Station } from '@/types/models'
import { loadStations } from '@/utils/stationDict'
import { useEChart } from '@/composables/useEChart'
import { RevenuePeriodType } from '@/utils/enums'

const route = useRoute()

const stations = ref<Station[]>([])
const stationId = ref('')
const periodType = ref<RevenuePeriodType>(RevenuePeriodType.DAY)
const date = ref(todayStr())
const loading = ref(false)
const summary = ref<RevenueSummary | null>(null)
const trend = ref<RevenueTrendPoint[]>([])
const detail = ref<RevenueDetailRow[]>([])
const meta = ref<EmsStationMeta | null>(null)
const chartEl = ref<HTMLElement>()

const PERIODS = [
  { key: RevenuePeriodType.DAY, label: '日' },
  { key: RevenuePeriodType.MONTH, label: '月' },
  { key: RevenuePeriodType.YEAR, label: '年' },
] as const

function todayStr(): string {
  const d = new Date()
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

async function load(): Promise<void> {
  if (!stationId.value) {
    summary.value = null
    trend.value = []
    detail.value = []
    return
  }
  loading.value = true
  try {
    const params = { stationId: stationId.value, periodType: periodType.value, date: date.value }
    const [s, t, d] = await Promise.all([
      emsApi.revenueSummary(params),
      periodType.value === RevenuePeriodType.DAY ? Promise.resolve([] as RevenueTrendPoint[]) : emsApi.revenueTrend(params),
      periodType.value === RevenuePeriodType.DAY ? emsApi.revenueDetail({ stationId: stationId.value, date: date.value }) : Promise.resolve([] as RevenueDetailRow[]),
    ])
    summary.value = s
    trend.value = t
    detail.value = d
    meta.value = await emsApi.revenueMetaGet(stationId.value)
    refreshChart()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    loading.value = false
  }
}

/** 复用项目既有 ECharts 生命周期组合式（init/ResizeObserver/dispose，Dashboard/EmsPlan 同款） */
const { render: renderChart } = useEChart(chartEl)

function fmtKwh(v: number | null | undefined): string {
  return v == null ? '—' : `${v.toFixed(1)} kWh`
}

function fmtYuan(v: number | null | undefined): string {
  return v == null ? '—' : `¥${v.toFixed(2)}`
}

const metaVisible = ref(false)
const savingMeta = ref(false)
const metaForm = reactive<{ investmentAmount: number | null; installDate: string | null }>({ investmentAmount: null, installDate: null })

function onChange(): void { void load() }

function openMeta(): void {
  metaForm.investmentAmount = meta.value?.investmentAmount ?? null
  metaForm.installDate = meta.value?.installDate ?? null
  metaVisible.value = true
}

async function saveMeta(): Promise<void> {
  if (metaForm.investmentAmount == null || metaForm.investmentAmount <= 0) {
    ElMessage.warning('请输入投资额（大于 0）')
    return
  }
  savingMeta.value = true
  try {
    await emsApi.revenueMetaPut({ stationId: stationId.value, investmentAmount: metaForm.investmentAmount, installDate: metaForm.installDate ?? undefined })
    ElMessage.success('已保存')
    metaVisible.value = false
    void load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    savingMeta.value = false
  }
}

onMounted(async () => {
  try {
    stations.value = await loadStations()
    const preset = route.query.station
    if (preset) {
      stationId.value = String(preset)
      void load()
    }
  } catch {
    // 电站加载失败由页面空态兜底
  }
})

/** 组装当前视图的 ECharts option 并渲染（日=逐槽、月/年=趋势点）。renderChart 已在 Step 1 绑定 useEChart 组合式。 */
function refreshChart(): void {
  const isDay = periodType.value === RevenuePeriodType.DAY
  // DAY 视图：按 time 分组聚合（同刻多设备/多行合并为一柱），series 与 x 轴类目 1:1 对齐
  const grouped = new Map<string, { charge: number; discharge: number; revenue: number }>()
  if (isDay) {
    for (const r of detail.value) {
      const acc = grouped.get(r.time) ?? { charge: 0, discharge: 0, revenue: 0 }
      if (r.action === 'CHARGE') acc.charge += r.energyKwh
      else if (r.action === 'DISCHARGE') acc.discharge += r.energyKwh
      acc.revenue += r.revenue
      grouped.set(r.time, acc)
    }
  }
  const times = isDay ? [...grouped.keys()] : trend.value.map((t) => t.label)
  const charge = isDay ? [...grouped.values()].map((a) => a.charge) : trend.value.map((t) => t.chargeEnergy)
  const discharge = isDay ? [...grouped.values()].map((a) => a.discharge) : trend.value.map((t) => t.dischargeEnergy)
  const revenue = isDay ? [...grouped.values()].map((a) => a.revenue) : trend.value.map((t) => t.revenue)
  renderChart({
    tooltip: { trigger: 'axis' },
    legend: { data: ['充电量', '放电量', '收益'] },
    grid: { left: 60, right: 60, top: 40, bottom: 40 },
    xAxis: { type: 'category', data: times, boundaryGap: true },
    yAxis: [{ type: 'value', name: 'kWh' }, { type: 'value', name: '元' }],
    series: [
      { name: '充电量', type: 'bar', stack: 'energy', data: charge, itemStyle: { color: '#3b82f6' } },
      { name: '放电量', type: 'bar', stack: 'energy', data: discharge, itemStyle: { color: '#f59e0b' } },
      { name: '收益', type: 'line', yAxisIndex: 1, data: revenue, smooth: true, itemStyle: { color: '#10b981' } },
    ],
  })
}
</script>

<template>
  <div class="ex-page">
    <header class="ex-page-head">
      <div class="head-title">
        <h1 class="ex-title">收益核算</h1>
        <p class="ex-sub">实际遥测口径 · 峰谷套利收益 = 放电电量×峰价 − 充电电量×谷价 · 需量节省 = 需量费率×（未削峰最大需量 − 实际最大需量）</p>
      </div>
      <el-button v-if="stationId" type="primary" @click="openMeta">设置投资额</el-button>
    </header>

    <section class="ex-card filter-card">
      <el-form inline @submit.prevent>
        <el-form-item label="电站">
          <el-select v-model="stationId" placeholder="选择电站" filterable clearable style="width: 260px" @change="onChange">
            <el-option v-for="s in stations" :key="s.stationId" :label="s.stationName" :value="s.stationId" />
          </el-select>
        </el-form-item>
        <el-form-item label="维度">
          <el-radio-group v-model="periodType" @change="onChange">
            <el-radio-button v-for="p in PERIODS" :key="p.key" :value="p.key">{{ p.label }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="日期">
          <el-date-picker v-model="date" :type="periodType === RevenuePeriodType.DAY ? 'date' : periodType === RevenuePeriodType.MONTH ? 'month' : 'year'"
            value-format="YYYY-MM-DD" :clearable="false" @change="onChange" />
        </el-form-item>
      </el-form>
    </section>

    <section class="ex-card kpi-grid" v-loading="loading">
      <div class="kpi"><span class="kpi-label">充电量</span><span class="kpi-num">{{ fmtKwh(summary?.chargeEnergy) }}</span></div>
      <div class="kpi"><span class="kpi-label">放电量</span><span class="kpi-num">{{ fmtKwh(summary?.dischargeEnergy) }}</span></div>
      <div class="kpi"><span class="kpi-label">总电量</span><span class="kpi-num">{{ fmtKwh(summary?.totalEnergy) }}</span></div>
      <div class="kpi"><span class="kpi-label">套利收益</span><span class="kpi-num">{{ fmtYuan(summary?.arbitrageRevenue) }}</span></div>
      <div class="kpi"><span class="kpi-label">需量节省</span><span class="kpi-num">{{ fmtYuan(summary?.demandSavings) }}</span></div>
      <div class="kpi"><span class="kpi-label">累计收益</span><span class="kpi-num">{{ fmtYuan(summary?.totalRevenue) }}</span></div>
      <div class="kpi kpi-roi"><span class="kpi-label">回本周期</span>
        <span class="kpi-num">{{ summary?.hasInvestment ? (summary.paybackYears == null ? '—' : summary.paybackYears + ' 年') : '未设置投资额' }}</span>
      </div>
    </section>

    <section class="ex-card chart-card">
      <div ref="chartEl" class="chart" role="img" aria-label="收益趋势曲线"></div>
    </section>

    <section v-if="periodType === RevenuePeriodType.DAY" class="ex-card table-card">
      <h3 class="ex-section">单日逐槽明细</h3>
      <el-table :data="detail" size="small" empty-text="该日无遥测或方向均未知">
        <el-table-column prop="time" label="时刻" width="90" />
        <el-table-column prop="action" label="方向" width="100" />
        <el-table-column prop="energyKwh" label="能量 (kWh)" align="right" />
        <el-table-column prop="price" label="电价 (元/kWh)" align="right" />
        <el-table-column prop="revenue" label="收益 (元)" align="right" />
        <el-table-column prop="source" label="方向来源" width="110" />
      </el-table>
    </section>

    <el-dialog v-model="metaVisible" title="设置投资额" width="440px">
      <el-form label-width="100px" @submit.prevent>
        <el-form-item label="投资额 (元)" required>
          <el-input-number v-model="metaForm.investmentAmount" :min="0" :precision="0" :step="10000" style="width: 100%" />
        </el-form-item>
        <el-form-item label="投运日期">
          <el-date-picker v-model="metaForm.installDate" type="date" value-format="YYYY-MM-DD" placeholder="投运日期" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="metaVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingMeta" @click="saveMeta">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* KPI 卡片网格（kpi-* 非全局类，需本页定义；Dashboard 同款视觉） */
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(170px, 1fr));
  gap: 14px;
}
.kpi {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.kpi-label {
  font-size: 13px;
  color: var(--ex-ink-2);
}
.kpi-num {
  font-size: 22px;
  font-weight: 600;
  color: var(--ex-ink);
  font-variant-numeric: tabular-nums;
}
.kpi-roi .kpi-num {
  font-size: 18px;
}
.chart {
  height: 360px;
  width: 100%;
}
.ex-section {
  font-size: 15px;
  font-weight: 600;
  color: var(--ex-ink);
  margin: 0 0 12px;
}
</style>
