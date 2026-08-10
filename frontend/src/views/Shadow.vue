<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { deviceApi } from '@/api/device'
import { productApi } from '@/api/product'
import { shadowApi } from '@/api/shadow'
import type { DesiredResult, Device, Product, ShadowView } from '@/types/models'

// ---------------- 产品/设备联动选择 ----------------
const selectedProductKey = ref('')
const productOptions = ref<Product[]>([])
const deviceOptions = ref<Device[]>([])
const deviceId = ref('')

async function loadProducts(): Promise<void> {
  try {
    const page = await productApi.page({ pageNum: 1, pageSize: 200 })
    productOptions.value = page.records ?? []
  } catch (e) {
    ElMessage.error(`产品加载失败：${e instanceof Error ? e.message : String(e)}`)
  }
}

async function onProductChange(productKey: string): Promise<void> {
  deviceId.value = ''
  deviceOptions.value = []
  if (!productKey) return
  try {
    const page = await deviceApi.page({ pageNum: 1, pageSize: 200, productKey })
    deviceOptions.value = page.records ?? []
  } catch (e) {
    ElMessage.error(`设备加载失败：${e instanceof Error ? e.message : String(e)}`)
  }
}

onMounted(() => void loadProducts())

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

function parseDeviceId(): string | null {
  const v = deviceId.value
  if (!v || !/^\d+$/.test(v)) {
    ElMessage.warning('请选择设备')
    return null
  }
  return v
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
  <div class="ex-page">
    <header class="ex-page-head">
      <div class="head-title">
        <h1 class="ex-title">设备影子</h1>
        <p class="ex-sub">设备上报状态（reported）与平台期望状态（desired）的双端比对与期望下发</p>
      </div>
      <el-form inline class="query-bar" @submit.prevent>
        <el-form-item label="产品" class="qi">
          <el-select
            v-model="selectedProductKey"
            placeholder="选择产品"
            filterable
            clearable
            style="width: 180px"
            @change="onProductChange"
          >
            <el-option v-for="p in productOptions" :key="p.productKey" :label="p.productName" :value="p.productKey" />
          </el-select>
        </el-form-item>
        <el-form-item label="设备" class="qi">
          <el-select
            v-model="deviceId"
            placeholder="选择设备"
            filterable
            clearable
            style="width: 220px"
            :disabled="!selectedProductKey"
          >
            <el-option v-for="d in deviceOptions" :key="d.deviceId" :label="d.deviceName" :value="d.deviceId" />
          </el-select>
        </el-form-item>
        <el-button type="primary" :loading="loading" @click="query">查询影子</el-button>
      </el-form>
    </header>

    <template v-if="hasView">
      <!-- 仪表读数带 -->
      <section class="ex-readout-band" style="--ro-cols: 4" aria-label="影子版本信息">
        <div class="ex-readout">
          <span class="ex-readout-label">设备 ID</span>
          <span class="ex-readout-value md"><b>{{ view!.deviceId }}</b></span>
        </div>
        <div class="ex-readout">
          <span class="ex-readout-label">乐观锁版本</span>
          <span class="ex-readout-value md steel"><b>{{ view!.version ?? '不存在' }}</b></span>
        </div>
        <div class="ex-readout">
          <span class="ex-readout-label">reported 属性</span>
          <span class="ex-readout-value md"><b>{{ reportedKeys.length }}</b><em>项</em></span>
        </div>
        <div class="ex-readout">
          <span class="ex-readout-label">desired 属性</span>
          <span class="ex-readout-value md discharge"><b>{{ desiredKeys.length }}</b><em>项</em></span>
        </div>
      </section>

      <section class="dual-cols">
        <div class="ex-card">
          <div class="ex-card-head">
            <h2 class="ex-card-title">reported · 设备上报状态</h2>
          </div>
          <el-empty v-if="reportedKeys.length === 0" description="暂无 reported 数据" :image-size="60" />
          <el-table v-else :data="reportedKeys.map((k) => ({ k, v: display(view!.reported[k]) }))" size="small">
            <el-table-column prop="k" label="属性" width="180" />
            <el-table-column prop="v" label="值" show-overflow-tooltip />
          </el-table>
        </div>

        <div class="ex-card">
          <div class="ex-card-head">
            <h2 class="ex-card-title">desired · 期望状态</h2>
          </div>
          <el-empty v-if="desiredKeys.length === 0" description="暂无 desired 数据" :image-size="60" />
          <el-table v-else :data="desiredKeys.map((k) => ({ k, v: display(view!.desired[k]) }))" size="small">
            <el-table-column prop="k" label="属性" width="180" />
            <el-table-column prop="v" label="值" show-overflow-tooltip />
          </el-table>
        </div>
      </section>

      <section class="ex-card editor-card">
        <div class="ex-card-head">
          <h2 class="ex-card-title">设置 desired</h2>
          <span class="editor-note">值可填 JSON 或字符串，例如 5000 / {"level":3}</span>
        </div>
        <el-form label-width="0" class="editor-form">
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
      </section>
    </template>

    <section v-else class="ex-card empty-card">
      <el-empty description="输入设备 ID 查询影子状态" :image-size="72" />
    </section>
  </div>
</template>

<style scoped>
.query-bar {
  display: flex;
  align-items: flex-start;
  gap: 4px;
}
.query-bar .qi {
  margin-bottom: 0;
}
.dual-cols {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 14px;
  align-items: start;
}
.editor-card {
  padding-bottom: 16px;
}
.editor-note {
  font-size: 12px;
  color: var(--ex-ink-3);
  font-variant-numeric: tabular-nums;
}
.editor-form {
  padding: 14px 18px 0;
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
  padding: 0 18px;
  margin-top: 6px;
}
.delta-alert {
  margin: 12px 18px 0;
}
.empty-card {
  padding: 40px 0;
}
@media (max-width: 900px) {
  .dual-cols {
    grid-template-columns: 1fr;
  }
}
</style>
