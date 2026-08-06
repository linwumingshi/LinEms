<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { shadowApi } from '@/api/shadow'
import type { DesiredResult, ShadowView } from '@/types/models'

const deviceId = ref<number | undefined>(undefined)
const loading = ref(false)
const view = ref<ShadowView | null>(null)
const lastDelta = ref<DesiredResult | null>(null)

// desired 编辑器：动态 key-value 行
interface KVRows {
  key: string
  value: string
}
const rows = ref<KVRows[]>([])

const hasView = computed(() => view.value !== null)
const reportedKeys = computed(() => (view.value ? Object.keys(view.value.reported) : []))
const desiredKeys = computed(() => (view.value ? Object.keys(view.value.desired) : []))

function parseDeviceId(): number | null {
  if (deviceId.value === undefined || deviceId.value <= 0) {
    ElMessage.warning('请输入合法的设备 ID')
    return null
  }
  return Math.trunc(deviceId.value)
}

async function query(): Promise<void> {
  const id = parseDeviceId()
  if (id === null) return
  loading.value = true
  lastDelta.value = null
  try {
    view.value = await shadowApi.getShadow(id)
    // 初始化 desired 编辑器为现有 desired（含值类型可编辑为字符串）
    rows.value = Object.entries(view.value.desired).map(([k, v]) => ({
      key: k,
      value: typeof v === 'object' ? JSON.stringify(v) : String(v),
    }))
    if (rows.value.length === 0) addRow()
    ElMessage.success(`影子查询成功（版本 ${view.value.version ?? '不存在'}）`)
  } catch (e) {
    view.value = null
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    loading.value = false
  }
}

function addRow(): void {
  rows.value.push({ key: '', value: '' })
}

function removeRow(index: number): void {
  rows.value.splice(index, 1)
}

function buildDesired(): Record<string, unknown> | null {
  const desired: Record<string, unknown> = {}
  for (const row of rows.value) {
    const k = row.key.trim()
    if (!k) {
      ElMessage.warning('存在空的属性 key，已忽略（如需删除请整行移除）')
      continue
    }
    desired[k] = parseValue(row.value)
  }
  if (Object.keys(desired).length === 0) {
    ElMessage.warning('请至少填写一个期望属性')
    return null
  }
  return desired
}

/** 尽力按 JSON 解析值，失败按字符串处理 */
function parseValue(raw: string): unknown {
  const t = raw.trim()
  if (t === '') return ''
  try {
    return JSON.parse(t)
  } catch {
    return t
  }
}

async function submitDesired(): Promise<void> {
  const id = parseDeviceId()
  if (id === null) return
  const desired = buildDesired()
  if (desired === null) return
  loading.value = true
  try {
    lastDelta.value = await shadowApi.setDesired(id, desired)
    // 重新拉取合并视图展示最新状态
    view.value = await shadowApi.getShadow(id)
    ElMessage.success(`desired 下发成功，delta 属性数：${Object.keys(lastDelta.value.delta).length}`)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    loading.value = false
  }
}

/** 展示 reported/desired 值（对象序列化为 JSON） */
function display(v: unknown): string {
  if (v === null || v === undefined) return '-'
  return typeof v === 'object' ? JSON.stringify(v) : String(v)
}
</script>

<template>
  <div class="page-card">
    <el-card shadow="never">
      <template #header>影子查询 / desired 下发</template>
      <el-form :inline="true" @submit.prevent>
        <el-form-item label="设备 ID">
          <el-input-number
            v-model="deviceId"
            :min="1"
            :controls="false"
            placeholder="请输入设备ID"
            style="width: 200px"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="query">查询影子</el-button>
        </el-form-item>
      </el-form>

      <template v-if="hasView">
        <el-descriptions :column="2" border size="small" class="desc">
          <el-descriptions-item label="设备 ID">{{ view!.deviceId }}</el-descriptions-item>
          <el-descriptions-item label="乐观锁版本">{{ view!.version ?? '不存在' }}</el-descriptions-item>
        </el-descriptions>

        <el-row :gutter="12">
          <el-col :xs="24" :md="12">
            <el-card shadow="never" class="inner-card">
              <template #header>reported（设备上报状态）</template>
              <el-empty v-if="reportedKeys.length === 0" description="暂无 reported 数据" :image-size="60" />
              <el-table v-else :data="reportedKeys.map((k) => ({ k, v: display(view!.reported[k]) }))" size="small">
                <el-table-column prop="k" label="属性" width="180" />
                <el-table-column prop="v" label="值" show-overflow-tooltip />
              </el-table>
            </el-card>
          </el-col>

          <el-col :xs="24" :md="12">
            <el-card shadow="never" class="inner-card">
              <template #header>desired（期望状态）</template>
              <el-empty v-if="desiredKeys.length === 0" description="暂无 desired 数据" :image-size="60" />
              <el-table v-else :data="desiredKeys.map((k) => ({ k, v: display(view!.desired[k]) }))" size="small">
                <el-table-column prop="k" label="属性" width="180" />
                <el-table-column prop="v" label="值" show-overflow-tooltip />
              </el-table>
            </el-card>
          </el-col>
        </el-row>

        <el-divider content-position="left">设置 desired</el-divider>
        <el-form label-width="0">
          <div v-for="(row, index) in rows" :key="index" class="row-line">
            <el-input v-model="row.key" placeholder="属性名（如 power）" class="row-key" />
            <el-input v-model="row.value" placeholder="属性值（JSON 或字符串，如 5000）" class="row-val" />
            <el-button type="danger" :icon="'Delete'" @click="removeRow(index)" circle />
          </div>
        </el-form>
        <div class="row-actions">
          <el-button :icon="'Plus'" @click="addRow">添加属性</el-button>
          <el-button type="primary" :loading="loading" @click="submitDesired">下发 desired</el-button>
        </div>

        <el-alert
          v-if="lastDelta"
          :title="`下发成功：delta（需同步设备）属性 ${Object.keys(lastDelta.delta).length} 个`"
          :description="Object.keys(lastDelta.delta).join('、') || '无差异'"
          type="success"
          show-icon
          :closable="false"
          class="delta-alert"
        />
      </template>
    </el-card>
  </div>
</template>

<style scoped>
.desc {
  margin-bottom: 12px;
}
.inner-card {
  margin-bottom: 12px;
}
.row-line {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}
.row-key {
  width: 220px;
}
.row-val {
  flex: 1;
}
.row-actions {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}
.delta-alert {
  margin-top: 12px;
}
</style>
