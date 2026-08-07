<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { emsApi } from '@/api/ems'
import { useEChart } from '@/composables/useEChart'
import type { EmsPlan, EmsPlanPoint } from '@/types/models'

const list = ref<EmsPlan[]>([])
const total = ref(0)
const pageNo = ref(1)
const pageSize = ref(10)
const drawerVisible = ref(false)
const chartEl = ref<HTMLElement>()
const { render } = useEChart(chartEl)
const currentPoints = ref<EmsPlanPoint[]>([])

async function load() {
  try {
    const data = await emsApi.planPage({ pageNo: pageNo.value, pageSize: pageSize.value })
    list.value = data.records
    total.value = data.total
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  }
}

async function viewDetail(row: EmsPlan) {
  drawerVisible.value = true
  try {
    currentPoints.value = await emsApi.planPoints(row.planId)
    render({
      tooltip: { trigger: 'axis' },
      xAxis: { type: 'category', data: currentPoints.value.map(p => p.time) },
      yAxis: { type: 'value', name: '功率 kW' },
      series: [{
        type: 'bar',
        data: currentPoints.value.map(p => ({
          value: p.powerKw,
          itemStyle: { color: p.action === 'CHARGE' ? '#67c23a' : p.action === 'DISCHARGE' ? '#f56c6c' : '#909399' },
        })),
      }],
    })
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  }
}

async function dispatch(row: EmsPlan) {
  try {
    await emsApi.dispatch(row.planId)
    ElMessage.success('已下发')
    load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <el-card>
      <el-table :data="list" border>
        <el-table-column prop="planId" label="计划 ID" width="90" />
        <el-table-column prop="planDate" label="计划日期" width="120" />
        <el-table-column prop="stationId" label="电站" width="90" />
        <el-table-column prop="strategyId" label="策略" width="90" />
        <el-table-column prop="totalEnergy" label="总量 kWh" width="120" />
        <el-table-column prop="status" label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 2 ? 'success' : row.status === 1 ? 'primary' : 'info'">
              {{ { 0: '待执行', 1: '执行中', 2: '完成', 3: '已取消' }[row.status as number] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200">
          <template #default="{ row }">
            <el-button size="small" @click="viewDetail(row)">点序图</el-button>
            <el-button size="small" type="success" @click="dispatch(row)" v-if="row.status === 0">下发</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination v-model:current-page="pageNo" v-model:page-size="pageSize" :total="total" @change="load" layout="total, prev, pager, next" />
    </el-card>

    <el-drawer v-model="drawerVisible" title="充放电计划点序" size="60%">
      <div ref="chartEl" style="height: 400px"></div>
    </el-drawer>
  </div>
</template>
