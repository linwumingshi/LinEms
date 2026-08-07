<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { alarmApi } from '@/api/alarm'
import AlarmLevelTag from '@/components/AlarmLevelTag.vue'
import { useEChart } from '@/composables/useEChart'
import { levelText, statusTag, statusText, summarizeRecords, toLocal, typeText } from '@/utils/alarmFormat'
import type { AlarmRecord } from '@/types/models'

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

onMounted(load)
</script>

<template>
  <div class="ex-page">
    <el-alert v-if="error" type="error" :closable="false" show-icon class="err-alert">
      驾驶舱数据加载失败：{{ error }}。请确认网关（127.0.0.1:8000）与告警服务已启动。
    </el-alert>

    <header class="ex-page-head">
      <div class="head-title">
        <h1 class="ex-title">设备监控</h1>
        <p class="ex-sub">告警驾驶舱 · 状态口径为精确计数，图表口径为近 500 条样本窗口</p>
      </div>
    </header>

    <!-- 仪表读数带 -->
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
