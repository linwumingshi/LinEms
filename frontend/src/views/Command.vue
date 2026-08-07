<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { commandApi } from '@/api/command'
import type { CommandView } from '@/types/models'
import { toLocal } from '@/utils/alarmFormat'

// ---------------- 下发表单 ----------------
const form = ref({
  productKey: 'std-energy-storage',
  deviceName: '',
  command: 'setPower',
  commandType: 2,
  timeoutMs: 15000,
  maxRetry: 3,
  createBy: 0,
})
const paramsRows = ref<Array<{ key: string; value: string }>>([{ key: 'power', value: '5000' }])
const submitting = ref(false)

// 常用物模型服务（示例：储能柜控制）
const commandOptions = ['setPower', 'startCharge', 'stopCharge', 'setDischargePower', 'getStatus']

function addParamRow(): void {
  paramsRows.value.push({ key: '', value: '' })
}
function removeParamRow(index: number): void {
  paramsRows.value.splice(index, 1)
}
function buildParams(): Record<string, unknown> {
  const params: Record<string, unknown> = {}
  for (const row of paramsRows.value) {
    const k = row.key.trim()
    if (!k) continue
    params[k] = parseValue(row.value)
  }
  return params
}
function parseValue(raw: string): unknown {
  const t = raw.trim()
  if (t === '') return ''
  try {
    return JSON.parse(t)
  } catch {
    return t
  }
}

// 最近下发（本会话内存）
const recent = ref<CommandView[]>([])
const lastCreated = ref<CommandView | null>(null)

async function submit(): Promise<void> {
  if (!form.value.deviceName.trim()) {
    ElMessage.warning('请输入设备名（deviceName）')
    return
  }
  submitting.value = true
  try {
    const created = await commandApi.create({
      productKey: form.value.productKey.trim(),
      deviceName: form.value.deviceName.trim(),
      command: form.value.command,
      commandType: form.value.commandType,
      params: buildParams(),
      timeoutMs: form.value.timeoutMs,
      maxRetry: form.value.maxRetry,
      createBy: form.value.createBy,
    })
    lastCreated.value = created
    recent.value.unshift(created)
    if (recent.value.length > 50) recent.value = recent.value.slice(0, 50)
    ElMessage.success(`指令已创建：${created.commandId}（状态 ${created.stateName}）`)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    submitting.value = false
  }
}

// ---------------- 查询 ----------------
const queryId = ref('')
const detail = ref<CommandView | null>(null)
const querying = ref(false)

async function queryDetail(commandId?: string): Promise<void> {
  const id = commandId ?? queryId.value.trim()
  if (!id) {
    ElMessage.warning('请输入指令 ID')
    return
  }
  querying.value = true
  try {
    detail.value = await commandApi.detail(id)
    // 同步刷新最近列表里对应行
    const idx = recent.value.findIndex((c) => c.commandId === id)
    if (idx >= 0) recent.value[idx] = detail.value
    ElMessage.success(`查询成功：${detail.value.stateName}`)
  } catch (e) {
    detail.value = null
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    querying.value = false
  }
}

// 状态机展示
const stateSteps = ['CREATED', 'SENT', 'DEVICE_RECEIVED', 'EXECUTING', 'SUCCESS']
const terminalStates = ['SUCCESS', 'FAILED', 'TIMEOUT']
function currentStep(view: CommandView): number {
  if (view.stateName === 'SUCCESS') return 4
  if (view.stateName === 'FAILED' || view.stateName === 'TIMEOUT') return 4
  const idx = stateSteps.indexOf(view.stateName)
  return idx >= 0 ? idx : 0
}
const isTerminal = computed(() => detail.value !== null && terminalStates.includes(detail.value.stateName))
const detailError = computed(() => (detail.value?.errorMsg ? `${detail.value.errorCode ?? ''} ${detail.value.errorMsg}` : '-'))
</script>

<template>
  <div class="ex-page">
    <header class="ex-page-head">
      <div class="head-title">
        <h1 class="ex-title">指令中心</h1>
        <p class="ex-sub">下行控制指令下发 · 状态机跟踪（创建 → 已发送 → 设备收到 → 执行 → 成功）</p>
      </div>
    </header>

    <section class="dual-cols">
      <!-- 下发表单 -->
      <div class="ex-card form-card">
        <div class="ex-card-head">
          <h2 class="ex-card-title">指令下发</h2>
        </div>
        <el-form label-width="110px" size="default" class="command-form">
          <el-form-item label="productKey" required>
            <el-input v-model="form.productKey" placeholder="产品标识" />
          </el-form-item>
          <el-form-item label="deviceName" required>
            <el-input v-model="form.deviceName" placeholder="设备名（设备表 device_name）" />
          </el-form-item>
          <el-form-item label="命令">
            <el-select v-model="form.command" allow-create filterable style="width: 100%">
              <el-option v-for="c in commandOptions" :key="c" :label="c" :value="c" />
            </el-select>
          </el-form-item>
          <el-form-item label="指令类型">
            <el-radio-group v-model="form.commandType">
              <el-radio :value="1">读取</el-radio>
              <el-radio :value="2">控制</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="参数">
            <div class="params">
              <div v-for="(row, index) in paramsRows" :key="index" class="row-line">
                <el-input v-model="row.key" placeholder="参数名" class="row-key" />
                <el-input v-model="row.value" placeholder="参数值（JSON 或字符串）" class="row-val" />
                <el-button type="danger" :icon="'Delete'" circle @click="removeParamRow(index)" />
              </div>
              <el-button :icon="'Plus'" size="small" @click="addParamRow">添加参数</el-button>
            </div>
          </el-form-item>
          <el-form-item label="超时(ms)">
            <el-input-number v-model="form.timeoutMs" :min="1000" :step="1000" />
          </el-form-item>
          <el-form-item label="最大重试">
            <el-input-number v-model="form.maxRetry" :min="0" :max="10" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="submitting" @click="submit">下发指令</el-button>
          </el-form-item>
        </el-form>

        <el-alert v-if="lastCreated" :title="`已创建：${lastCreated.commandId}`" type="success" show-icon :closable="false" class="created-alert">
          <template #default>
            状态 <b>{{ lastCreated.stateName }}</b>（{{ lastCreated.state }}），
            deviceId={{ lastCreated.deviceId }}，超时 {{ lastCreated.timeoutMs }}ms，重试 {{ lastCreated.retryCount }}/{{ lastCreated.maxRetry }}
          </template>
        </el-alert>
      </div>

      <!-- 查询 + 状态机 -->
      <div class="right-col">
        <div class="ex-card track-card">
          <div class="ex-card-head">
            <h2 class="ex-card-title">状态跟踪</h2>
            <el-form :inline="true" class="query-inline" @submit.prevent>
              <el-input v-model="queryId" placeholder="指令 ID（如 202608061530001）" style="width: 240px" />
              <el-button type="primary" :loading="querying" @click="queryDetail()">查询</el-button>
            </el-form>
          </div>

          <template v-if="detail">
            <div class="track-body">
              <el-steps :active="currentStep(detail)" align-center finish-status="success" class="steps">
                <el-step v-for="s in stateSteps" :key="s" :title="s" />
              </el-steps>
              <el-descriptions :column="2" border size="small" class="desc">
                <el-descriptions-item label="指令 ID">{{ detail.commandId }}</el-descriptions-item>
                <el-descriptions-item label="状态">
                  <el-tag :type="isTerminal ? (detail.stateName === 'SUCCESS' ? 'success' : 'danger') : 'primary'" size="small">
                    {{ detail.stateName }}
                  </el-tag>
                </el-descriptions-item>
                <el-descriptions-item label="deviceId">{{ detail.deviceId }}</el-descriptions-item>
                <el-descriptions-item label="command">{{ detail.command }}</el-descriptions-item>
                <el-descriptions-item label="发送时间"><span class="ex-num">{{ toLocal(detail.sentTime) }}</span></el-descriptions-item>
                <el-descriptions-item label="完成时间"><span class="ex-num">{{ toLocal(detail.finishTime) }}</span></el-descriptions-item>
                <el-descriptions-item label="错误信息" :span="2">{{ detailError }}</el-descriptions-item>
              </el-descriptions>
            </div>
          </template>
          <el-empty v-else description="查询指令状态" :image-size="60" class="track-empty" />
        </div>

        <!-- 最近指令 -->
        <div class="ex-card recent-card">
          <div class="ex-card-head">
            <h2 class="ex-card-title">本会话最近下发</h2>
          </div>
          <el-table :data="recent" size="small" empty-text="暂无下发记录">
            <el-table-column prop="commandId" label="指令 ID" show-overflow-tooltip min-width="150" />
            <el-table-column prop="command" label="命令" width="110" />
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag :type="row.stateName === 'SUCCESS' ? 'success' : row.stateName === 'FAILED' || row.stateName === 'TIMEOUT' ? 'danger' : 'primary'" size="small">
                  {{ row.stateName }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="deviceId" label="设备ID" width="90" />
            <el-table-column label="操作" width="90">
              <template #default="{ row }">
                <el-button link type="primary" @click="queryDetail(row.commandId)">刷新</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.dual-cols {
  display: grid;
  grid-template-columns: minmax(0, 5fr) minmax(0, 7fr);
  gap: 14px;
  align-items: start;
}
.form-card {
  padding-bottom: 16px;
}
.command-form {
  padding: 14px 18px 0;
}
.created-alert {
  margin: 4px 18px 0;
}
.right-col {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.track-card {
  padding-bottom: 16px;
}
.query-inline {
  gap: 6px;
}
.track-body {
  padding: 14px 18px 0;
}
.steps {
  margin-bottom: 18px;
}
.steps :deep(.el-step__title) {
  font-size: 12px;
  color: var(--ex-ink-2);
}
.steps :deep(.el-step__title.is-process) {
  color: var(--ex-steel);
  font-weight: 600;
}
.desc {
  margin-top: 4px;
}
.track-empty {
  padding: 32px 0;
}
.recent-card {
  padding-bottom: 8px;
}
@media (max-width: 980px) {
  .dual-cols {
    grid-template-columns: 1fr;
  }
}
</style>
