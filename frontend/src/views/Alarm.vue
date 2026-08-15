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

// ---------------- 规则新增/编辑 ----------------
const ruleDialog = ref(false)
const ruleSaving = ref(false)
const ruleEditId = ref<string | null>(null)

/** 规则条件/恢复的表单态（按 triggerType 渲染属性或事件字段） */
const ruleForm = reactive({
  ruleCode: '',
  ruleName: '',
  triggerType: 1 as 1 | 2,
  metric: '',
  op: 'GTE' as 'GT' | 'GTE' | 'LT' | 'LTE' | 'EQ' | 'NEQ',
  value: '',
  windowSec: 60,
  event: '',
  severity: 3,
  silenceSeconds: 300,
  recoveryEnabled: false,
  recMetric: '',
  recOp: 'LT' as 'GT' | 'GTE' | 'LT' | 'LTE' | 'EQ' | 'NEQ',
  recValue: '',
  status: 1,
  description: '',
})

const opOptions = [
  { value: 'GT', label: '>' },
  { value: 'GTE', label: '≥' },
  { value: 'LT', label: '<' },
  { value: 'LTE', label: '≤' },
  { value: 'EQ', label: '=' },
  { value: 'NEQ', label: '≠' },
]

/** 打开新增（空表单） */
function openRuleCreate(): void {
  ruleEditId.value = null
  resetRuleForm()
  ruleDialog.value = true
}

/** 打开编辑（回填；condition/recovery JSON 解析到表单） */
function openRuleEdit(row: AlarmRule): void {
  ruleEditId.value = row.ruleId
  resetRuleForm()
  ruleForm.ruleCode = row.ruleCode
  ruleForm.ruleName = row.ruleName
  ruleForm.triggerType = row.triggerType === 2 ? 2 : 1
  ruleForm.severity = row.severity
  ruleForm.silenceSeconds = row.silenceSeconds
  ruleForm.status = row.status
  ruleForm.description = row.description ?? ''
  try {
    const cond = JSON.parse(row.condition) as Record<string, unknown>
    if (ruleForm.triggerType === 2) {
      ruleForm.event = String(cond.event ?? '')
    } else {
      ruleForm.metric = String(cond.metric ?? '')
      ruleForm.op = (cond.op as never) || 'GTE'
      ruleForm.value = String(cond.value ?? '')
      ruleForm.windowSec = Number(cond.windowSec ?? 60)
    }
  } catch {
    // condition 解析失败保留空表单，由用户重填
  }
  if (row.recovery) {
    try {
      const rec = JSON.parse(row.recovery) as Record<string, unknown>
      ruleForm.recoveryEnabled = true
      ruleForm.recMetric = String(rec.metric ?? '')
      ruleForm.recOp = (rec.op as never) || 'LT'
      ruleForm.recValue = String(rec.value ?? '')
    } catch {
      ruleForm.recoveryEnabled = false
    }
  }
  ruleDialog.value = true
}

function resetRuleForm(): void {
  ruleForm.ruleCode = ''
  ruleForm.ruleName = ''
  ruleForm.triggerType = 1
  ruleForm.metric = ''
  ruleForm.op = 'GTE'
  ruleForm.value = ''
  ruleForm.windowSec = 60
  ruleForm.event = ''
  ruleForm.severity = 3
  ruleForm.silenceSeconds = 300
  ruleForm.recoveryEnabled = false
  ruleForm.recMetric = ''
  ruleForm.recOp = 'LT'
  ruleForm.recValue = ''
  ruleForm.status = 1
  ruleForm.description = ''
}

/** 保存（新增/编辑共用；condition/recovery 组装 JSON） */
async function saveRule(): Promise<void> {
  if (!ruleForm.ruleName.trim()) { ElMessage.warning('请填写规则名称'); return }
  if (ruleForm.triggerType === 1) {
    if (!ruleForm.metric.trim() || ruleForm.value === '') { ElMessage.warning('属性规则需填写属性与阈值'); return }
  } else if (!ruleForm.event.trim()) {
    ElMessage.warning('事件规则需填写事件标识'); return
  }
  const condition = ruleForm.triggerType === 1
    ? JSON.stringify({ metric: ruleForm.metric.trim(), op: ruleForm.op, value: ruleForm.value, windowSec: ruleForm.windowSec })
    : JSON.stringify({ event: ruleForm.event.trim() })
  const recovery = ruleForm.recoveryEnabled && ruleForm.recMetric.trim()
    ? JSON.stringify({ metric: ruleForm.recMetric.trim(), op: ruleForm.recOp, value: ruleForm.recValue })
    : null
  const body = {
    ruleCode: ruleForm.ruleCode.trim(),
    ruleName: ruleForm.ruleName.trim(),
    triggerType: ruleForm.triggerType,
    condition,
    severity: ruleForm.severity,
    silenceSeconds: ruleForm.silenceSeconds,
    recovery,
    status: ruleForm.status,
    description: ruleForm.description.trim() || null,
  }
  ruleSaving.value = true
  try {
    if (ruleEditId.value) {
      await alarmApi.updateRule(ruleEditId.value, body)
      ElMessage.success('规则已更新')
    } else {
      await alarmApi.createRule(body)
      ElMessage.success('规则已创建')
    }
    ruleDialog.value = false
    rules.value = await alarmApi.rules()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    ruleSaving.value = false
  }
}

/** 删除规则（二次确认） */
async function removeRule(row: AlarmRule): Promise<void> {
  try {
    await ElMessageBox.confirm(`确定删除告警规则「${row.ruleName}」？已产生的告警记录不受影响`, '删除确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await alarmApi.deleteRule(row.ruleId)
    ElMessage.success('规则已删除')
    rules.value = await alarmApi.rules()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
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
    <el-drawer v-model="rulesDrawer" title="告警规则（启用中）" size="640px">
      <div class="rules-head">
        <span class="rules-tip">规则为静态配置，命中后产生告警记录（数据源：es_alarm.iot_alarm_rule）</span>
        <el-button size="small" type="primary" @click="openRuleCreate">+ 新增规则</el-button>
      </div>
      <el-table :data="rules" v-loading="rulesLoading" size="small">
        <el-table-column prop="ruleCode" label="规则码" width="140" />
        <el-table-column prop="ruleName" label="名称" show-overflow-tooltip />
        <el-table-column label="类型" width="70" align="center">
          <template #default="{ row }">{{ row.triggerType === 2 ? '事件' : '属性' }}</template>
        </el-table-column>
        <el-table-column label="级别" width="70">
          <template #default="{ row }"><AlarmLevelTag :level="row.severity" /></template>
        </el-table-column>
        <el-table-column label="状态" width="70" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110" align="center">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openRuleEdit(row)">编辑</el-button>
            <el-button link type="danger" size="small" @click="removeRule(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>

    <!-- 规则新增/编辑表单 -->
    <el-dialog v-model="ruleDialog" :title="ruleEditId ? `编辑告警规则 · ${ruleForm.ruleName}` : '新增告警规则'" width="540px">
      <el-form label-width="96px">
        <el-form-item label="规则编码" required>
          <el-input v-model="ruleForm.ruleCode" placeholder="如 ALM_TEMP_HIGH（租户内唯一）" :disabled="!!ruleEditId" />
        </el-form-item>
        <el-form-item label="规则名称" required>
          <el-input v-model="ruleForm.ruleName" placeholder="如 电芯高温告警" />
        </el-form-item>
        <el-form-item label="触发类型" required>
          <el-radio-group v-model="ruleForm.triggerType">
            <el-radio :value="1">属性比较</el-radio>
            <el-radio :value="2">事件</el-radio>
          </el-radio-group>
        </el-form-item>

        <!-- 属性触发条件 -->
        <template v-if="ruleForm.triggerType === 1">
          <el-form-item label="属性 metric" required>
            <el-input v-model="ruleForm.metric" placeholder="物模型属性标识，如 cellTemp" style="width: 220px" />
          </el-form-item>
          <el-form-item label="比较符" required>
            <el-select v-model="ruleForm.op" style="width: 120px">
              <el-option v-for="o in opOptions" :key="o.value" :label="o.label" :value="o.value" />
            </el-select>
            <el-input v-model="ruleForm.value" placeholder="阈值" style="width: 140px; margin-left: 6px" />
          </el-form-item>
          <el-form-item label="持续秒">
            <el-input-number v-model="ruleForm.windowSec" :min="0" :max="86400" />
            <span class="field-hint">连续超阈需持续该时长（0=立即）</span>
          </el-form-item>
        </template>
        <el-form-item v-else label="事件标识" required>
          <el-input v-model="ruleForm.event" placeholder="物模型事件标识，如 bmsFault" style="width: 240px" />
        </el-form-item>

        <el-form-item label="告警级别">
          <el-select v-model="ruleForm.severity" style="width: 140px">
            <el-option label="提示" :value="1" />
            <el-option label="一般" :value="2" />
            <el-option label="严重" :value="3" />
            <el-option label="危急" :value="4" />
          </el-select>
        </el-form-item>
        <el-form-item label="静默秒">
          <el-input-number v-model="ruleForm.silenceSeconds" :min="0" :max="86400" />
          <span class="field-hint">触发后静默期内不重复告警</span>
        </el-form-item>

        <el-form-item label="恢复条件">
          <el-switch v-model="ruleForm.recoveryEnabled" />
          <span class="field-hint">属性回到正常区间即自动恢复</span>
        </el-form-item>
        <template v-if="ruleForm.recoveryEnabled">
          <el-form-item label="恢复 metric">
            <el-input v-model="ruleForm.recMetric" placeholder="如 cellTemp" style="width: 200px" />
          </el-form-item>
          <el-form-item label="恢复判定">
            <el-select v-model="ruleForm.recOp" style="width: 110px">
              <el-option v-for="o in opOptions" :key="o.value" :label="o.label" :value="o.value" />
            </el-select>
            <el-input v-model="ruleForm.recValue" placeholder="恢复阈值" style="width: 130px; margin-left: 6px" />
          </el-form-item>
        </template>

        <el-form-item label="启用">
          <el-switch v-model="ruleForm.status" :active-value="1" :inactive-value="0" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="ruleForm.description" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ruleDialog = false">取消</el-button>
        <el-button type="primary" :loading="ruleSaving" @click="saveRule">保存</el-button>
      </template>
    </el-dialog>
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
.rules-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}
.rules-tip {
  font-size: 12px;
  color: var(--ex-ink-3);
}
.field-hint {
  font-size: 12px;
  color: var(--ex-ink-3);
  margin-left: 6px;
}
</style>
