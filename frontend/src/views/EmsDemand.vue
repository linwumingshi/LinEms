<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { emsApi } from '@/api/ems'
import type { DemandSavingsView, EmsDemandConfig, EmsDemandRecord, Station } from '@/types/models'
import { loadStations } from '@/utils/stationDict'
import { useEChart } from '@/composables/useEChart'
import { RevenuePeriodType } from '@/utils/enums'

const route = useRoute()

const stations = ref<Station[]>([])
const stationId = ref('')
const periodType = ref<RevenuePeriodType>(RevenuePeriodType.DAY)
const date = ref(todayStr())
const loading = ref(false)
const records = ref<EmsDemandRecord[]>([])
const savings = ref<DemandSavingsView | null>(null)
const config = ref<EmsDemandConfig | null>(null)
const chartEl = ref<HTMLElement>()

const PERIODS = [
  { key: RevenuePeriodType.DAY, label: '日' },
  { key: RevenuePeriodType.MONTH, label: '月' },
  { key: RevenuePeriodType.YEAR, label: '年' },
] as const

const ACTION_TEXT: Record<string, string> = {
  NONE: '未超限',
  SHED: '已削峰',
  SHED_FAILED: '削峰失败',
  ALARM_ONLY: '仅告警',
}

function todayStr(): string {
  const d = new Date()
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

async function load(): Promise<void> {
  if (!stationId.value) {
    records.value = []
    savings.value = null
    config.value = null
    refreshChart()
    return
  }
  loading.value = true
  try {
    const [r, s, c] = await Promise.all([
      emsApi.demandRecords(stationId.value, date.value),
      emsApi.demandSavings({ stationId: stationId.value, periodType: periodType.value, date: date.value }),
      emsApi.demandConfigGet(stationId.value),
    ])
    records.value = r
    savings.value = s
    config.value = c
    refreshChart()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    loading.value = false
  }
}

const { render: renderChart } = useEChart(chartEl)

function fmtKw(v: number | null | undefined): string {
  return v == null ? '—' : `${v.toFixed(1)} kW`
}
function fmtYuan(v: number | null | undefined): string {
  return v == null ? '—' : `¥${v.toFixed(2)}`
}

const violationRows = () => records.value.filter((r) => r.overLimit)

/** 96 槽位需量柱状图 + 限值红色虚线；超限槽位红色高亮。 */
function refreshChart(): void {
  const over = new Set(violationRows().map((r) => r.windowStart))
  renderChart({
    tooltip: { trigger: 'axis' },
    legend: { data: ['需量', '限值'] },
    grid: { left: 60, right: 20, top: 40, bottom: 40 },
    xAxis: { type: 'category', data: records.value.map((r) => r.windowStart.slice(11, 16)), boundaryGap: true },
    yAxis: { type: 'value', name: 'kW' },
    series: [
      {
        name: '需量',
        type: 'bar',
        barMaxWidth: 12,
        data: records.value.map((r) => ({
          value: r.demandKw,
          itemStyle: over.has(r.windowStart) ? { color: '#ef4444' } : { color: '#3b82f6' },
        })),
        markLine: config.value?.demandLimitKw != null
          ? {
              symbol: 'none',
              data: [{ yAxis: config.value.demandLimitKw }],
              lineStyle: { color: '#ef4444', type: 'dashed' },
              label: { formatter: '限值 {c} kW' },
            }
          : undefined,
      },
    ],
  })
}

const configVisible = ref(false)
const savingConfig = ref(false)
const configForm = reactive<{ demandLimitKw: number | null; demandRate: number | null }>({
  demandLimitKw: null,
  demandRate: null,
})

function onChange(): void { void load() }

function openConfig(): void {
  configForm.demandLimitKw = config.value?.demandLimitKw ?? null
  configForm.demandRate = config.value?.demandRate ?? null
  configVisible.value = true
}

async function saveConfig(): Promise<void> {
  if (configForm.demandLimitKw == null || configForm.demandLimitKw <= 0) {
    ElMessage.warning('请输入需量限值（大于 0）')
    return
  }
  savingConfig.value = true
  try {
    await emsApi.demandConfigPut({ stationId: stationId.value, demandLimitKw: configForm.demandLimitKw, demandRate: configForm.demandRate })
    ElMessage.success('已保存')
    configVisible.value = false
    void load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    savingConfig.value = false
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
</script>

<template>
  <div class="ex-page">
    <header class="ex-page-head">
      <div class="head-title">
        <h1 class="ex-title">需量管理</h1>
        <p class="ex-sub">15min 固定槽位需量检测 · 超限实时削峰 · 基本电费节省估算</p>
      </div>
      <el-button v-if="stationId" type="primary" @click="openConfig">需量配置</el-button>
    </header>

    <section class="ex-card filter-card">
      <el-form inline @submit.prevent>
        <el-form-item label="电站">
          <el-select v-model="stationId" placeholder="选择电站" filterable clearable style="width: 260px" @change="onChange">
            <el-option v-for="s in stations" :key="s.stationId" :label="s.stationName" :value="s.stationId" />
          </el-select>
        </el-form-item>
        <el-form-item label="周期">
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
      <div class="kpi"><span class="kpi-label">实际最大需量</span><span class="kpi-num">{{ fmtKw(savings?.actualMaxKw) }}</span></div>
      <div class="kpi"><span class="kpi-label">未削峰需量</span><span class="kpi-num">{{ fmtKw(savings?.unshavedMaxKw) }}</span></div>
      <div class="kpi"><span class="kpi-label">需量节省</span><span class="kpi-num">{{ fmtYuan(savings?.savings) }}</span></div>
      <div class="kpi"><span class="kpi-label">超限槽位数</span><span class="kpi-num">{{ violationRows().length }}</span></div>
      <div class="kpi"><span class="kpi-label">需量限值</span><span class="kpi-num">{{ fmtKw(config?.demandLimitKw) }}</span></div>
    </section>

    <section class="ex-card chart-card">
      <div ref="chartEl" class="chart" role="img" aria-label="需量曲线"></div>
    </section>

    <section class="ex-card table-card">
      <h3 class="ex-section">超限明细</h3>
      <el-table :data="violationRows()" size="small" empty-text="该日无超限槽位">
        <el-table-column prop="windowStart" label="时间窗" width="150">
          <template #default="{ row }">{{ row.windowStart.slice(5, 16) }} ~ {{ row.windowEnd.slice(11, 16) }}</template>
        </el-table-column>
        <el-table-column prop="demandKw" label="需量 (kW)" align="right" />
        <el-table-column prop="limitKw" label="限值 (kW)" align="right" />
        <el-table-column label="超限量 (kW)" align="right">
          <template #default="{ row }">{{ (row.demandKw - (row.limitKw ?? 0)).toFixed(2) }}</template>
        </el-table-column>
        <el-table-column prop="shavedKw" label="削峰 (kW)" align="right" />
        <el-table-column label="动作" width="110">
          <template #default="{ row }">
            <el-tag :type="row.action === 'SHED' ? 'success' : row.action === 'SHED_FAILED' ? 'danger' : 'warning'" size="small">
              {{ ACTION_TEXT[row.action] ?? row.action }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-dialog v-model="configVisible" title="需量配置" width="440px">
      <el-form label-width="110px" @submit.prevent>
        <el-form-item label="需量限值 (kW)" required>
          <el-input-number v-model="configForm.demandLimitKw" :min="1" :precision="2" :step="100" style="width: 100%" />
        </el-form-item>
        <el-form-item label="需量费率 (元/kW·月)">
          <el-input-number v-model="configForm.demandRate" :min="0" :precision="4" :step="1" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="configVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingConfig" @click="saveConfig">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* 复用 EmsRevenue.vue 的 kpi-grid/kpi/chart/ex-section 视觉（kpi-* 非全局类，需本页定义） */
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
