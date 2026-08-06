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
      title: { text: '近 7 日告警趋势（样本）', left: 'center', textStyle: { fontSize: 13 } },
      tooltip: { trigger: 'axis' },
      grid: { left: 40, right: 20, top: 40, bottom: 30 },
      xAxis: { type: 'category', data: s.trend.map((t) => t.day) },
      yAxis: { type: 'value', minInterval: 1 },
      series: [
        {
          name: '触发数',
          type: 'line',
          smooth: true,
          areaStyle: { opacity: 0.15 },
          data: s.trend.map((t) => t.count),
          itemStyle: { color: '#409eff' },
        },
      ],
    })

    const levelSeries = Object.keys(s.levelCount)
      .map(Number)
      .sort((a, b) => a - b)
      .map((lv) => ({ name: levelText(lv), value: s.levelCount[lv] }))
    renderLevel({
      title: { text: '告警级别分布（样本）', left: 'center', textStyle: { fontSize: 13 } },
      tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
      legend: { bottom: 0 },
      series: [{ name: '级别', type: 'pie', radius: ['35%', '62%'], data: levelSeries }],
    })

    renderStatus({
      title: { text: '告警状态分布（样本）', left: 'center', textStyle: { fontSize: 13 } },
      tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
      legend: { bottom: 0 },
      series: [
        {
          name: '状态',
          type: 'pie',
          radius: '65%',
          data: [
            { name: '触发中', value: s.active, itemStyle: { color: '#f56c6c' } },
            { name: '已恢复', value: s.recovered, itemStyle: { color: '#67c23a' } },
            { name: '已确认', value: s.acked, itemStyle: { color: '#909399' } },
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
  <div class="page-card">
    <el-alert v-if="error" type="error" :closable="false" show-icon class="err-alert">
      驾驶舱数据加载失败：{{ error }}。请确认网关（127.0.0.1:8000）与告警服务已启动。
    </el-alert>

    <!-- 指标卡 -->
    <el-row :gutter="12" v-loading="loading">
      <el-col :xs="12" :sm="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-value danger">{{ stats.active }}</div>
          <div class="stat-label">触发中告警</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-value success">{{ stats.recovered }}</div>
          <div class="stat-label">已恢复告警</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-value info">{{ stats.acked }}</div>
          <div class="stat-label">已确认告警</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-value primary">{{ summary.deviceCount }}</div>
          <div class="stat-label">样本涉及设备数</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 图表区 -->
    <el-row :gutter="12" class="chart-row">
      <el-col :xs="24" :sm="24" :md="12">
        <el-card shadow="never" class="chart-card">
          <div ref="trendEl" class="chart"></div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="12" :md="6">
        <el-card shadow="never" class="chart-card">
          <div ref="levelEl" class="chart"></div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="12" :md="6">
        <el-card shadow="never" class="chart-card">
          <div ref="statusEl" class="chart"></div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 最近告警 -->
    <el-card shadow="never" class="chart-card">
      <template #header>
        <span>最近告警（样本窗口）</span>
      </template>
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
          <template #default="{ row }">{{ toLocal(row.triggeredTime) }}</template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.err-alert {
  margin-bottom: 12px;
}
.stat-card {
  margin-bottom: 12px;
  text-align: center;
}
.stat-value {
  font-size: 30px;
  font-weight: 700;
  line-height: 1.2;
}
.stat-value.danger {
  color: #f56c6c;
}
.stat-value.success {
  color: #67c23a;
}
.stat-value.info {
  color: #909399;
}
.stat-value.primary {
  color: #409eff;
}
.stat-label {
  color: #909399;
  font-size: 13px;
  margin-top: 4px;
}
.chart-row {
  margin-bottom: 12px;
}
.chart-card {
  margin-bottom: 12px;
}
.chart {
  height: 260px;
}
</style>
