<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { storeToRefs } from 'pinia'
import { alarmApi } from '@/api/alarm'
import AlarmLevelTag from '@/components/AlarmLevelTag.vue'
import { useAlarmStore } from '@/stores/alarm'
import type { AlarmRecord, AlarmRule } from '@/types/models'
import { statusTag, statusText, toLocal, tsToLocal, typeText } from '@/utils/alarmFormat'

const alarmStore = useAlarmStore()
const { liveEvents, connected } = storeToRefs(alarmStore)

// ---------------- 查询条件 ----------------
const filters = reactive<{
  level: number | undefined
  status: number | undefined
  deviceId: number | undefined
  timeRange: [string, string] | null
}>({
  level: undefined,
  status: undefined,
  deviceId: undefined,
  timeRange: null,
})

const tableData = ref<AlarmRecord[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const acking = ref('')

async function load(): Promise<void> {
  loading.value = true
  try {
    const res = await alarmApi.records({
      level: filters.level,
      status: filters.status,
      deviceId: filters.deviceId,
      startTime: filters.timeRange ? filters.timeRange[0] : undefined,
      endTime: filters.timeRange ? filters.timeRange[1] : undefined,
      page: page.value,
      size: size.value,
    })
    tableData.value = res.records
    total.value = res.total
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    loading.value = false
  }
}

function resetFilters(): void {
  filters.level = undefined
  filters.status = undefined
  filters.deviceId = undefined
  filters.timeRange = null
  page.value = 1
  void load()
}

async function ack(row: AlarmRecord): Promise<void> {
  try {
    await ElMessageBox.confirm(`确认告警「${row.message}」？确认后状态变为已确认（不可再确认）。`, '告警确认', {
      type: 'warning',
      confirmButtonText: '确认',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  acking.value = row.alarmEventId
  try {
    await alarmApi.ack(row.alarmEventId, 'ops-001')
    ElMessage.success('已确认')
    alarmStore.consume(row.alarmEventId)
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    acking.value = ''
  }
}

// 确认实时推送事件
async function ackLive(eventId: string): Promise<void> {
  acking.value = eventId
  try {
    await alarmApi.ack(eventId, 'ops-001')
    ElMessage.success('已确认')
    alarmStore.consume(eventId)
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    acking.value = ''
  }
}

// ---------------- 规则抽屉 ----------------
const rulesDrawer = ref(false)
const rules = ref<AlarmRule[]>([])
const rulesLoading = ref(false)

async function openRules(): Promise<void> {
  rulesDrawer.value = true
  rulesLoading.value = true
  try {
    rules.value = await alarmApi.rules()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    rulesLoading.value = false
  }
}

function extText(r: AlarmRecord): string {
  const obj = r.ext
  if (!obj || Object.keys(obj).length === 0) return '-'
  return Object.entries(obj)
    .map(([k, v]) => `${k}=${typeof v === 'object' ? JSON.stringify(v) : v}`)
    .join('，')
}

onMounted(() => void load())
</script>

<template>
  <div class="page-card">
    <!-- 实时推送面板 -->
    <el-card shadow="never" class="live-card">
      <template #header>
        <div class="live-header">
          <span>实时告警推送</span>
          <el-tag :type="connected ? 'success' : 'danger'" size="small" effect="dark">
            {{ connected ? '已连接 /ws/alarm' : '未连接（自动重连中）' }}
          </el-tag>
        </div>
      </template>
      <el-empty
        v-if="liveEvents.length === 0"
        description="暂无实时告警，等待设备上报触发告警规则…"
        :image-size="60"
      />
      <el-timeline v-else class="live-timeline">
        <el-timeline-item
          v-for="e in liveEvents"
          :key="e.alarmEventId"
          :type="e.status === 'ACTIVE' ? 'danger' : 'success'"
          :timestamp="tsToLocal(e.ts)"
          :hollow="e.status === 'RECOVERED'"
        >
          <div class="live-item">
            <AlarmLevelTag :level="e.level" />
            <span class="live-rule">{{ e.ruleCode }}</span>
            <el-tag size="small" :type="e.status === 'ACTIVE' ? 'danger' : 'success'">
              {{ e.status === 'ACTIVE' ? '触发' : '恢复' }}
            </el-tag>
            <span class="live-msg">{{ e.message }}</span>
            <el-button
              v-if="e.status === 'ACTIVE'"
              link
              type="primary"
              size="small"
              :loading="acking === e.alarmEventId"
              @click="ackLive(e.alarmEventId)"
            >
              确认
            </el-button>
          </div>
        </el-timeline-item>
      </el-timeline>
    </el-card>

    <!-- 查询区 -->
    <el-card shadow="never" class="filter-card">
      <el-form :inline="true" @submit.prevent>
        <el-form-item label="级别">
          <el-select v-model="filters.level" placeholder="全部" clearable style="width: 110px">
            <el-option label="提示" :value="1" />
            <el-option label="一般" :value="2" />
            <el-option label="严重" :value="3" />
            <el-option label="危急" :value="4" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="filters.status" placeholder="全部" clearable style="width: 110px">
            <el-option label="触发中" :value="0" />
            <el-option label="已恢复" :value="1" />
            <el-option label="已确认" :value="2" />
          </el-select>
        </el-form-item>
        <el-form-item label="设备ID">
          <el-input-number v-model="filters.deviceId" :min="1" :controls="false" placeholder="全部" style="width: 130px" />
        </el-form-item>
        <el-form-item label="触发时间">
          <el-date-picker
            v-model="filters.timeRange"
            type="datetimerange"
            value-format="YYYY-MM-DDTHH:mm:ss"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
            :default-time="[new Date(2000, 0, 1, 0, 0, 0), new Date(2000, 0, 1, 23, 59, 59)]"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="page = 1; void load()">查询</el-button>
          <el-button @click="resetFilters">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 告警表格 -->
    <el-card shadow="never">
      <template #header>
        <div class="table-header">
          <span>告警记录</span>
          <el-button link type="primary" @click="openRules">告警规则配置</el-button>
        </div>
      </template>
      <el-table :data="tableData" v-loading="loading" size="default" empty-text="暂无告警记录">
        <el-table-column prop="ruleCode" label="规则" width="140" show-overflow-tooltip />
        <el-table-column label="级别" width="80">
          <template #default="{ row }"><AlarmLevelTag :level="row.level" /></template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="type" label="类型" width="70">
          <template #default="{ row }">{{ typeText(row.type) }}</template>
        </el-table-column>
        <el-table-column prop="deviceId" label="设备ID" width="90" />
        <el-table-column prop="message" label="内容" show-overflow-tooltip min-width="180" />
        <el-table-column prop="ext" label="扩展" show-overflow-tooltip min-width="140">
          <template #default="{ row }">{{ extText(row) }}</template>
        </el-table-column>
        <el-table-column label="触发时间" width="160">
          <template #default="{ row }">{{ toLocal(row.triggeredTime) }}</template>
        </el-table-column>
        <el-table-column label="恢复/确认" width="150">
          <template #default="{ row }">
            <span v-if="row.status === 1">恢复 {{ toLocal(row.recoveredTime) }}</span>
            <span v-else-if="row.status === 2">
              {{ row.ackedBy }} @ {{ toLocal(row.ackTime) }}
            </span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status !== 2"
              link
              type="primary"
              :loading="acking === row.alarmEventId"
              @click="ack(row)"
            >
              确认
            </el-button>
            <span v-else class="acked">已确认</span>
          </template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          :total="total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          @current-change="load"
          @size-change="page = 1; void load()"
        />
      </div>
    </el-card>

    <!-- 规则抽屉 -->
    <el-drawer v-model="rulesDrawer" title="告警规则（启用中）" size="480px">
      <el-table :data="rules" v-loading="rulesLoading" size="small">
        <el-table-column prop="ruleCode" label="规则码" width="130" />
        <el-table-column prop="ruleName" label="名称" show-overflow-tooltip />
        <el-table-column label="级别" width="80">
          <template #default="{ row }"><AlarmLevelTag :level="row.severity" /></template>
        </el-table-column>
        <el-table-column prop="silenceSeconds" label="静默(s)" width="80" />
      </el-table>
    </el-drawer>
  </div>
</template>

<style scoped>
.live-card {
  margin-bottom: 12px;
}
.live-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.live-timeline {
  max-height: 260px;
  overflow-y: auto;
  padding-right: 8px;
}
.live-item {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.live-rule {
  font-weight: 600;
  color: #303133;
}
.live-msg {
  color: #606266;
  flex: 1;
  min-width: 120px;
}
.filter-card {
  margin-bottom: 12px;
}
.table-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}
.acked {
  color: #909399;
}
</style>
