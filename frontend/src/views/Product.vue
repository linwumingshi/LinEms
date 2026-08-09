<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { productApi } from '@/api/product'
import type { Product } from '@/types/models'
import { deviceTypeLabel, deviceTypeOptions, deviceTypeText, productStatusText, thingModelStatusText } from '@/utils/dicts'
import { toLocal } from '@/utils/alarmFormat'

const loading = ref(false)
const list = ref<Product[]>([])
const total = ref(0)
const pageNo = ref(1)
const pageSize = ref(20)
const query = ref({ deviceType: '', status: undefined as number | undefined, keyword: '' })

// 读数带（pageSize=1 轻量计数）
const readout = ref({ total: 0, enabled: 0, disabled: 0 })
async function loadReadout() {
  try {
    const [t, on, off] = await Promise.all([
      productApi.page({ pageNum: 1, pageSize: 1 }),
      productApi.page({ pageNum: 1, pageSize: 1, status: 1 }),
      productApi.page({ pageNum: 1, pageSize: 1, status: 0 }),
    ])
    readout.value = { total: t.total, enabled: on.total, disabled: off.total }
  } catch (e) { console.warn('产品统计加载失败', e) }
}

async function load() {
  loading.value = true
  try {
    const data = await productApi.page({
      pageNum: pageNo.value, pageSize: pageSize.value,
      deviceType: query.value.deviceType || undefined,
      status: query.value.status,
      keyword: query.value.keyword || undefined,
    })
    list.value = data.records
    total.value = data.total
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : String(e)) }
  finally { loading.value = false }
}
function search() { pageNo.value = 1; void load() }
function resetQuery() { query.value = { deviceType: '', status: undefined, keyword: '' }; pageNo.value = 1; void load() }

// 新增/编辑
const dialogVisible = ref(false)
const isEdit = ref(false)
const form = ref<Partial<Product>>({})
const errs = ref<Record<string, string>>({})
watch(
  () => [form.value.productKey, form.value.productName, form.value.deviceType],
  () => { errs.value = {} },
)
function openCreate() { form.value = { status: 1, protocol: 'MQTT', authType: 'SECRET' }; isEdit.value = false; dialogVisible.value = true }
function openEdit(row: Product) { form.value = { ...row }; isEdit.value = true; dialogVisible.value = true }
async function save() {
  errs.value = {}
  const e: Record<string, string> = {}
  if (!form.value.productKey?.trim()) e.productKey = '请输入 productKey'
  if (!form.value.productName?.trim()) e.productName = '请输入产品名称'
  if (!form.value.deviceType) e.deviceType = '请选择设备类型'
  if (Object.keys(e).length) { errs.value = e; return }
  try {
    if (isEdit.value) await productApi.update(form.value.productId!, form.value)
    else await productApi.create(form.value)
    ElMessage.success('保存成功')
    dialogVisible.value = false
    void load(); void loadReadout()
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : String(e)) }
}
async function remove(row: Product) {
  try { await ElMessageBox.confirm(`确定删除产品「${row.productName}」吗？`, '提示', { type: 'warning' }) } catch { return }
  try {
    await productApi.remove(row.productId)
    ElMessage.success('已删除')
    void load(); void loadReadout()
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : String(e)) }
}

// 物模型抽屉
const tmDrawer = ref(false)
const tmProduct = ref<Product | null>(null)
const tmVersion = ref('')
const tmSchema = ref('')
const tmStatus = ref(0)
const tmSaving = ref(false)
async function openThingModel(row: Product) {
  tmProduct.value = row
  tmDrawer.value = true
  tmVersion.value = ''
  tmSchema.value = '{\n  "properties": [],\n  "services": [],\n  "events": []\n}'
  tmStatus.value = 0
  try {
    const view = await productApi.thingModelGet(row.productId)
    tmVersion.value = view.version
    tmSchema.value = view.schemaJson
    tmStatus.value = view.status
  } catch {
    // 未发布：保留空模板提示
  }
}
function formatJson() {
  try { tmSchema.value = JSON.stringify(JSON.parse(tmSchema.value), null, 2) }
  catch { ElMessage.error('JSON 语法错误，无法格式化') }
}
function validateJson(): boolean {
  try { JSON.parse(tmSchema.value); return true }
  catch (e) { ElMessage.error(`JSON 语法错误：${e instanceof Error ? e.message : String(e)}`); return false }
}
async function publishModel() {
  if (!tmProduct.value) return
  if (!tmVersion.value.trim()) { ElMessage.warning('请填写版本号'); return }
  if (!validateJson()) return
  tmSaving.value = true
  try {
    const view = await productApi.thingModelSave(tmProduct.value.productId, {
      version: tmVersion.value.trim(), schemaJson: tmSchema.value,
    })
    tmStatus.value = view.status
    ElMessage.success(`物模型已保存（版本 ${view.version}${view.isCurrent === 1 ? '，当前生效' : ''}）`)
    void load()
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : String(e)) }
  finally { tmSaving.value = false }
}

onMounted(() => { void load(); void loadReadout() })
</script>

<template>
  <div class="ex-page">
    <header class="ex-page-head">
      <div class="head-title">
        <h1 class="ex-title">产品管理</h1>
        <p class="ex-sub">产品标识 / 设备类型 / 物模型版本 · 决定设备接入协议与认证方式</p>
      </div>
      <el-button type="primary" @click="openCreate">新增产品</el-button>
    </header>

    <section class="ex-readout-band" style="--ro-cols: 3" aria-label="产品统计">
      <div class="ex-readout"><span class="ex-readout-label">产品总数</span><span class="ex-readout-value md"><b>{{ readout.total }}</b></span></div>
      <div class="ex-readout"><span class="ex-readout-label">已启用</span><span class="ex-readout-value md charge"><b>{{ readout.enabled }}</b></span></div>
      <div class="ex-readout"><span class="ex-readout-label">已禁用</span><span class="ex-readout-value md"><b>{{ readout.disabled }}</b></span></div>
    </section>

    <section class="ex-card filter-card">
      <el-form inline @submit.prevent>
        <el-form-item label="设备类型">
          <el-select v-model="query.deviceType" clearable placeholder="全部" style="width: 180px">
            <el-option v-for="t in deviceTypeOptions" :key="t" :label="deviceTypeLabel(t)" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" clearable placeholder="全部" style="width: 120px">
            <el-option label="启用" :value="1" />
            <el-option label="禁用" :value="0" />
          </el-select>
        </el-form-item>
        <el-form-item label="关键字">
          <el-input v-model="query.keyword" placeholder="产品名 / 产品标识" clearable style="width: 220px" @keyup.enter="search" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="search">查询</el-button>
          <el-button @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>
    </section>

    <section class="ex-card table-card">
      <el-table :data="list" v-loading="loading" size="small" empty-text="暂无产品，点击右上角新增">
        <el-table-column prop="productKey" label="productKey" min-width="140" show-overflow-tooltip />
        <el-table-column prop="productName" label="产品名称" min-width="140" show-overflow-tooltip />
        <el-table-column prop="deviceType" label="设备类型" width="150">
          <template #default="{ row }"><el-tag size="small" effect="plain">{{ deviceTypeText(row.deviceType) }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="authType" label="认证" width="90" />
        <el-table-column label="物模型版本" width="110">
          <template #default="{ row }"><span class="ex-num">{{ row.modelVersion ?? '—' }}</span></template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">{{ productStatusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="150">
          <template #default="{ row }"><span class="ex-num">{{ toLocal(row.createTime) }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="primary" @click="openThingModel(row)">物模型</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="pager">
        <el-pagination v-model:current-page="pageNo" v-model:page-size="pageSize" :total="total"
          :page-sizes="[10, 20, 50]" layout="total, sizes, prev, pager, next"
          @size-change="pageNo = 1; void load()" @current-change="load" />
      </div>
    </section>

    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑产品' : '新增产品'" width="560px">
      <el-form label-width="110px">
        <el-form-item label="productKey" required :error="errs.productKey">
          <el-input v-model="form.productKey" placeholder="产品标识，如 snd_ess_pcs（修改影响已接入设备）" maxlength="64" />
        </el-form-item>
        <el-form-item label="产品名称" required :error="errs.productName">
          <el-input v-model="form.productName" placeholder="产品名称" maxlength="128" />
        </el-form-item>
        <el-form-item label="设备类型" required :error="errs.deviceType">
          <el-select v-model="form.deviceType" style="width: 100%">
            <el-option v-for="t in deviceTypeOptions" :key="t" :label="deviceTypeLabel(t)" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="认证方式">
          <el-select v-model="form.authType" style="width: 100%">
            <el-option label="密钥 SECRET" value="SECRET" />
            <el-option label="证书 CERT" value="CERT" />
          </el-select>
        </el-form-item>
        <el-form-item label="协议">
          <el-input v-model="form.protocol" placeholder="默认 MQTT" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">禁用</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="3" maxlength="512" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="tmDrawer" size="560px" :title="`物模型 · ${tmProduct?.productName ?? ''}`">
      <div class="tm-head">
        <span>当前状态：<el-tag size="small" :type="tmStatus === 1 ? 'success' : tmStatus === 0 ? 'info' : 'danger'">{{ thingModelStatusText(tmStatus) }}</el-tag></span>
      </div>
      <el-form label-width="70px" class="tm-form">
        <el-form-item label="版本号" required>
          <el-input v-model="tmVersion" placeholder="同版本=覆盖并生效，新版本=发布并切换当前" />
        </el-form-item>
      </el-form>
      <el-input v-model="tmSchema" type="textarea" :rows="14" class="tm-editor" spellcheck="false" />
      <div class="tm-actions">
        <el-button @click="formatJson">格式化</el-button>
        <el-button type="primary" :loading="tmSaving" @click="publishModel">保存发布</el-button>
      </div>
      <p class="tm-note">保存语义由后端处理：同版本覆盖置当前，异版本新增并切换当前，同时回写产品 model_version。</p>
    </el-drawer>
  </div>
</template>

<style scoped>
.filter-card { padding: 14px 18px 0; }
.filter-card :deep(.el-form-item) { margin-bottom: 14px; }
.table-card { padding-bottom: 10px; }
.pager { display: flex; justify-content: flex-end; margin-top: 12px; padding: 0 18px; }
.tm-form { margin-top: 4px; }
.tm-editor { font-family: 'Cascadia Mono', Consolas, monospace; font-size: 12px; }
.tm-actions { display: flex; gap: 8px; margin-top: 12px; }
.tm-note { font-size: 12px; color: var(--ex-ink-3); margin: 12px 0 0; }
</style>
