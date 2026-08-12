<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { storeToRefs } from 'pinia'
import { alarmApi } from '@/api/alarm'
import { deviceApi } from '@/api/device'
import { productApi } from '@/api/product'
import AlarmLevelTag from '@/components/AlarmLevelTag.vue'
import { useAlarmStore } from '@/stores/alarm'
import type { AlarmRecord, AlarmRule, Device, Product } from '@/types/models'
import { statusTag, statusText, toLocal, tsToLocal, typeText } from '@/utils/alarmFormat'
import { AlarmLevel, AlarmRecordStatus } from '@/utils/enums'

const alarmStore = useAlarmStore()
const { liveEvents, connected } = storeToRefs(alarmStore)

// ---------------- 产品/设备联动选择（筛选） ----------------
const selectedProductKey = ref('')
const productOptions = ref<Product[]>([])
const deviceOptions = ref<Device[]>([])

async function loadProducts(): Promise<void> {
  try {
    const page = await productApi.page({ pageNum: 1, pageSize: 200 })
    productOptions.value = page.records ?? []
  } catch (e) {
    ElMessage.error(`产品加载失败：${e instanceof Error ? e.message : String(e)}`)
  }
}

async function onProductChange(productKey: string): Promise<void> {
  filters.deviceId = undefined
  deviceOptions.value = []
  if (!productKey) return
  try {
    const page = await deviceApi.page({ pageNum: 1, pageSize: 200, productKey })
    deviceOptions.value = page.records ?? []
  } catch (e) {
    ElMessage.error(`设备加载失败：${e instanceof Error ? e.message : String(e)}`)
  }
}

// ---------------- 查询条件 ----------------
const filters = reactive<{
  level: number | undefined
  status: number | undefined
  deviceId: string | undefined
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

onMounted(() => {
  void load()
  void loadProducts()
})
</script>

<template>
  <div class="ex-page">
    <header class="ex-page-head">
      <div class="head-title">
        <h1 class="ex-title">告警中心</h1>
        <p class="ex-sub">实时推送 + 历史检索 · 级别/状态语义色与设备监控保持一致</p>
      </div>
      <el-button link type="primary" @click="openRules">告警规则配置</el-button>
    </header>

    <!-- 实时推送面板 -->
    <section class="ex-card live-card">
      <div class="ex-card-head">
        <h2 class="ex-card-title">实时告警推送</h2>
        <span class="ws-pill" :class="{ on: connected }">
          <span class="dot"></span>
          {{ connected ? '已连接 /ws/alarm' : '未连接（自动重连中）' }}
        </span>
      </div>
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
    </section>

    <!-- 查询区 -->
    <section class="ex-card filter-card">
      <el-form :inline="true" @submit.prevent>
        <el-form-item label="级别">
          <el-select v-model="filters.level" placeholder="全部" clearable style="width: 110px">
            <el-option label="提示" :value="AlarmLevel.INFO" />
            <el-option label="一般" :value="AlarmLevel.GENERAL" />
            <el-option label="严重" :value="AlarmLevel.MAJOR" />
            <el-option label="危急" :value="AlarmLevel.CRITICAL" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="filters.status" placeholder="全部" clearable style="width: 110px">
            <el-option label="触发中" :value="AlarmRecordStatus.TRIGGERING" />
            <el-option label="已恢复" :value="AlarmRecordStatus.RECOVERED" />
            <el-option label="已确认" :value="AlarmRecordStatus.ACKED" />
          </el-select>
        </el-form-item>
        <el-form-item label="设备">
          <el-select
            v-model="selectedProductKey"
            placeholder="产品"
            clearable
            filterable
            style="width: 130px"
            @change="onProductChange"
          >
            <el-option v-for="p in productOptions" :key="p.productKey" :label="p.productName" :value="p.productKey" />
          </el-select>
          <el-select
            v-model="filters.deviceId"
            placeholder="设备"
            clearable
            filterable
            style="width: 150px; margin-left: 6px"
            :disabled="!selectedProductKey"
          >
            <el-option v-for="d in deviceOptions" :key="d.deviceId" :label="d.deviceName" :value="d.deviceId" />
          </el-select>
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
    </section>

    <!-- 告警表格 -->
    <section class="ex-card table-card">
      <div class="ex-card-head">
        <h2 class="ex-card-title">告警记录</h2>
        <span class="table-total">共 {{ total }} 条</span>
      </div>
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
          <template #default="{ row }"><span class="ex-num">{{ toLocal(row.triggeredTime) }}</span></template>
        </el-table-column>
        <el-table-column label="恢复/确认" width="150">
          <template #default="{ row }">
            <span v-if="row.status === AlarmRecordStatus.RECOVERED" class="ex-num">恢复 {{ toLocal(row.recoveredTime) }}</span>
            <span v-else-if="row.status === AlarmRecordStatus.ACKED" class="ex-num">
              {{ row.ackedBy }} @ {{ toLocal(row.ackTime) }}
            </span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status !== AlarmRecordStatus.ACKED"
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
    </section>

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
.ws-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--ex-ink-2);
  border: 1px solid var(--ex-hair);
  border-radius: 999px;
  padding: 3px 10px;
  font-variant-numeric: tabular-nums;
}
.ws-pill .dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--ex-danger);
}
.ws-pill.on .dot {
  background: var(--ex-charge);
}
.ws-pill.on {
  color: var(--ex-charge);
  border-color: #cfe6d8;
  background: #f2f9f5;
}
.live-card {
  padding-bottom: 12px;
}
.live-timeline {
  max-height: 260px;
  overflow-y: auto;
  padding: 6px 18px 0;
}
.live-item {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.live-rule {
  font-weight: 600;
  color: var(--ex-ink);
}
.live-msg {
  color: var(--ex-ink-2);
  flex: 1;
  min-width: 120px;
}
.filter-card {
  padding: 4px 14px 0;
}
.filter-card :deep(.el-form-item) {
  margin-bottom: 12px;
}
.table-card {
  padding-bottom: 10px;
}
.table-total {
  font-size: 12px;
  color: var(--ex-ink-3);
  font-variant-numeric: tabular-nums;
}
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
  padding: 0 18px;
}
.acked {
  color: var(--ex-ink-3);
}
</style>
