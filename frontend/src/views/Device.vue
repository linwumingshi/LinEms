<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deviceApi } from '@/api/device'
import { productApi } from '@/api/product'
import { enterpriseApi } from '@/api/system'
import type { CredentialView, Device, Product, Station, SysEnterprise } from '@/types/models'
import { deviceStatusTag, deviceStatusText, deviceTypeLabel, deviceTypeOptions, deviceTypeText } from '@/utils/dicts'
import { toLocal } from '@/utils/alarmFormat'
import { loadStations, stationName } from '@/utils/stationDict'

const loading = ref(false)
const list = ref<Device[]>([])
const total = ref(0)
const pageNo = ref(1)
const pageSize = ref(20)
const query = ref({ deviceType: '', status: undefined as number | undefined, keyword: '', stationId: undefined as string | undefined })

const readout = ref({ total: 0, online: 0, offline: 0, disabled: 0 })
async function countByStatus(status?: number) {
  return (await deviceApi.page({ pageNum: 1, pageSize: 1, status })).total
}
async function loadReadout() {
  try {
    const [t, on, off, dis] = await Promise.all([countByStatus(), countByStatus(3), countByStatus(2), countByStatus(4)])
    readout.value = { total: t, online: on, offline: off, disabled: dis }
  } catch (e) { console.warn('设备统计加载失败', e) }
}

async function load() {
  loading.value = true
  try {
    const data = await deviceApi.page({
      pageNum: pageNo.value, pageSize: pageSize.value,
      deviceType: query.value.deviceType || undefined,
      status: query.value.status,
      keyword: query.value.keyword || undefined,
      stationId: query.value.stationId ?? undefined,
    })
    list.value = data.records
    total.value = data.total
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : String(e)) }
  finally { loading.value = false }
}
function search() { pageNo.value = 1; void load() }
function resetQuery() { query.value = { deviceType: '', status: undefined, keyword: '', stationId: undefined }; pageNo.value = 1; void load() }

// 新增/编辑
const dialogVisible = ref(false)
const isEdit = ref(false)
const form = ref<Partial<Device>>({})
const products = ref<Product[]>([])
const enterprises = ref<SysEnterprise[]>([])
const stations = ref<Station[]>([])
const errs = ref<Record<string, string>>({})
const narrowedTypes = computed(() => {
  const p = products.value.find((x) => x.productKey === form.value.productKey)
  return p?.deviceType ? [p.deviceType] : deviceTypeOptions
})
async function loadStationOptions() {
  try { stations.value = await loadStations() } catch { /* 名称回退裸 id，不阻断 */ }
}
watch(
  () => [form.value.deviceName, form.value.productKey, form.value.deviceType],
  () => { errs.value = {} },
)
async function loadOptions() {
  try {
    const [p, e] = await Promise.all([
      productApi.page({ pageNum: 1, pageSize: 100 }),
      enterpriseApi.list().catch(() => [] as SysEnterprise[]),
    ])
    products.value = p.records
    enterprises.value = e
  } catch { /* 选项加载失败不阻塞页面 */ }
}
function onProductChange(pk?: string) {
  const p = products.value.find((x) => x.productKey === pk)
  if (p) form.value.deviceType = p.deviceType
}
function openCreate() { form.value = { status: 0, protocol: 'MQTT', parentId: undefined }; isEdit.value = false; dialogVisible.value = true }
function openEdit(row: Device) { form.value = { ...row }; isEdit.value = true; dialogVisible.value = true }
async function save() {
  errs.value = {}
  const e: Record<string, string> = {}
  if (!form.value.deviceName?.trim()) e.deviceName = '设备名必填'
  else if (form.value.deviceName.includes('_') || form.value.deviceName.includes('&')) e.deviceName = 'deviceName 禁止包含 _ 或 &（接入契约）'
  if (!form.value.productKey) e.productKey = '请选择产品'
  if (!form.value.deviceType) e.deviceType = '请选择设备类型'
  if (Object.keys(e).length) { errs.value = e; return }
  try {
    if (isEdit.value) {
      await deviceApi.update(form.value.deviceId!, {
        deviceName: form.value.deviceName, deviceType: form.value.deviceType,
        stationId: form.value.stationId, status: form.value.status,
        firmwareVersion: form.value.firmwareVersion, mac: form.value.mac,
        ip: form.value.ip, sort: form.value.sort,
      })
    } else {
      const id = await deviceApi.create({
        deviceName: form.value.deviceName!.trim(),
        deviceType: form.value.deviceType!,
        productKey: form.value.productKey!,
        parentId: form.value.parentId ?? undefined,
        stationId: form.value.stationId ?? undefined,
        enterpriseId: form.value.enterpriseId ?? undefined,
        firmwareVersion: form.value.firmwareVersion || undefined,
        mac: form.value.mac || undefined,
        ip: form.value.ip || undefined,
        sort: form.value.sort ?? 0,
        status: form.value.status,
        protocol: form.value.protocol || 'MQTT',
      })
      ElMessage.success(`创建成功，设备 ID=${id}，凭据已生成——请打开该设备详情查看/复制`)
    }
    dialogVisible.value = false
    void load(); void loadReadout()
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : String(e)) }
}

// 详情抽屉 + 凭据
const drawerVisible = ref(false)
const detail = ref<Device | null>(null)
const cred = ref<CredentialView | null>(null)
const plainSecret = ref('')
async function openDetail(row: Device) {
  detail.value = row
  drawerVisible.value = true
  cred.value = null
  plainSecret.value = ''
  try {
    const [d, c] = await Promise.all([deviceApi.detail(row.deviceId), deviceApi.credential(row.deviceId)])
    detail.value = d
    cred.value = c
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : String(e)) }
}
async function regenerateSecret() {
  if (!detail.value) return
  try { await ElMessageBox.confirm('重新生成将吊销旧密钥，设备需用新密钥重连。确定继续吗？', '提示', { type: 'warning' }) } catch { return }
  try {
    cred.value = await deviceApi.regenerateCredential(detail.value.deviceId)
    plainSecret.value = cred.value.deviceSecret
    ElMessage.success('新密钥已生成（仅本次明文展示）')
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : String(e)) }
}
async function copySecret() {
  if (!plainSecret.value) return
  if (navigator.clipboard) {
    try {
      await navigator.clipboard.writeText(plainSecret.value)
      ElMessage.success('已复制')
    } catch {
      ElMessage.error('复制失败，请手动选择复制')
    }
  } else {
    ElMessage.warning('当前浏览器不支持剪贴板，请手动选择复制')
  }
}
function onlineSeconds(sec?: string | number | null): string {
  const n = sec == null || sec === '' ? 0 : Number(sec)
  if (!n || n <= 0) return '-'
  const d = Math.floor(n / 86400); const h = Math.floor((n % 86400) / 3600); const m = Math.floor((n % 3600) / 60)
  return `${d}天${h}时${m}分`
}

async function remove(row: Device) {
  try { await ElMessageBox.confirm(`确定删除设备「${row.deviceName}」吗？将级联删除其整棵子树并吊销凭据。`, '提示', { type: 'warning' }) } catch { return }
  try {
    await deviceApi.remove(row.deviceId)
    ElMessage.success('已删除')
    void load(); void loadReadout()
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : String(e)) }
}

onMounted(() => { void load(); void loadReadout(); void loadOptions(); void loadStationOptions() })
</script>

<template>
  <div class="ex-page">
    <header class="ex-page-head">
      <div class="head-title">
        <h1 class="ex-title">设备管理</h1>
        <p class="ex-sub">设备树统一建模 · 状态 0未注册~5封禁 · 凭据仅查询时脱敏展示</p>
      </div>
      <el-button type="primary" @click="openCreate">新增设备</el-button>
    </header>

    <section class="ex-readout-band" style="--ro-cols: 4" aria-label="设备状态统计">
      <div class="ex-readout"><span class="ex-readout-label">设备总数</span><span class="ex-readout-value md"><b>{{ readout.total }}</b></span></div>
      <div class="ex-readout"><span class="ex-readout-label">在线</span><span class="ex-readout-value md charge"><b>{{ readout.online }}</b></span></div>
      <div class="ex-readout"><span class="ex-readout-label">离线</span><span class="ex-readout-value md"><b>{{ readout.offline }}</b></span></div>
      <div class="ex-readout"><span class="ex-readout-label">禁用</span><span class="ex-readout-value md danger"><b>{{ readout.disabled }}</b></span></div>
    </section>

    <section class="ex-card filter-card">
      <el-form inline @submit.prevent>
        <el-form-item label="设备类型">
          <el-select v-model="query.deviceType" clearable placeholder="全部" style="width: 170px">
            <el-option v-for="t in deviceTypeOptions" :key="t" :label="deviceTypeLabel(t)" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" clearable placeholder="全部" style="width: 120px">
            <el-option v-for="i in [0, 1, 2, 3, 4, 5]" :key="i" :label="deviceStatusText(i)" :value="i" />
          </el-select>
        </el-form-item>
        <el-form-item label="电站">
          <el-select v-model="query.stationId" clearable filterable placeholder="全部" style="width: 170px">
            <el-option v-for="s in stations" :key="s.stationId" :label="s.stationName" :value="s.stationId" />
          </el-select>
        </el-form-item>
        <el-form-item label="关键字">
          <el-input v-model="query.keyword" placeholder="设备名模糊" clearable style="width: 200px" @keyup.enter="search" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="search">查询</el-button>
          <el-button @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>
    </section>

    <section class="ex-card table-card">
      <el-table :data="list" v-loading="loading" size="small" empty-text="暂无设备" @row-click="openDetail">
        <el-table-column prop="deviceId" label="deviceId" width="130" show-overflow-tooltip>
          <template #default="{ row }"><span class="ex-num">{{ row.deviceId }}</span></template>
        </el-table-column>
        <el-table-column prop="deviceName" label="设备名" min-width="140" show-overflow-tooltip />
        <el-table-column prop="productKey" label="productKey" width="130" show-overflow-tooltip />
        <el-table-column label="类型" width="130"><template #default="{ row }">{{ deviceTypeText(row.deviceType) }}</template></el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="deviceStatusTag(row.status)" size="small">{{ deviceStatusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="电站" min-width="110" show-overflow-tooltip>
          <template #default="{ row }">{{ stationName(row.stationId, stations) }}</template>
        </el-table-column>
        <el-table-column label="最近上线" width="150">
          <template #default="{ row }"><span class="ex-num">{{ toLocal(row.lastOnlineTime) }}</span></template>
        </el-table-column>
        <el-table-column label="创建时间" width="150">
          <template #default="{ row }"><span class="ex-num">{{ toLocal(row.createTime) }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click.stop="openDetail(row)">详情</el-button>
            <el-button link type="primary" @click.stop="openEdit(row)">编辑</el-button>
            <el-button link type="danger" @click.stop="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="pager">
        <el-pagination v-model:current-page="pageNo" v-model:page-size="pageSize" :total="total"
          :page-sizes="[10, 20, 50]" layout="total, sizes, prev, pager, next"
          @size-change="pageNo = 1; void load()" @current-change="load" />
      </div>
    </section>

    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑设备' : '新增设备'" width="600px">
      <el-form label-width="110px">
        <el-form-item label="设备名" required :error="errs.deviceName">
          <el-input v-model="form.deviceName" placeholder="如 sim-dev-000001（禁止 _ 与 &）" maxlength="128" :disabled="isEdit" />
        </el-form-item>
        <el-form-item label="产品" required :error="errs.productKey">
          <el-select v-model="form.productKey" filterable style="width: 100%" :disabled="isEdit" @change="onProductChange">
            <el-option v-for="p in products" :key="p.productKey" :label="`${p.productName} (${p.productKey})`" :value="p.productKey" />
          </el-select>
        </el-form-item>
        <el-form-item label="设备类型" required :error="errs.deviceType">
          <el-select v-model="form.deviceType" style="width: 100%" :disabled="isEdit || !!form.productKey">
            <el-option v-for="t in narrowedTypes" :key="t" :label="deviceTypeLabel(t)" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="所属企业">
          <el-select v-model="form.enterpriseId" clearable filterable placeholder="无" style="width: 100%" :disabled="isEdit">
            <el-option v-for="e in enterprises" :key="e.enterpriseId" :label="e.enterpriseName" :value="e.enterpriseId" />
          </el-select>
        </el-form-item>
        <el-form-item label="电站">
          <el-select v-model="form.stationId" clearable filterable placeholder="无" style="width: 100%">
            <el-option v-for="s in stations" :key="s.stationId" :label="s.stationName" :value="s.stationId" />
          </el-select>
        </el-form-item>
        <el-form-item label="固件版本">
          <el-input v-model="form.firmwareVersion" placeholder="如 v1.2.0" maxlength="64" />
        </el-form-item>
        <el-form-item label="MAC / IP">
          <el-input v-model="form.mac" placeholder="MAC" style="width: 48%" />
          <el-input v-model="form.ip" placeholder="IP" style="width: 48%; margin-left: 4%" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status" style="width: 200px">
            <el-option v-for="i in [0, 1, 2, 3, 4, 5]" :key="i" :label="deviceStatusText(i)" :value="i" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="drawerVisible" size="480px" :title="`设备详情 · ${detail?.deviceName ?? ''}`">
      <template v-if="detail">
        <el-descriptions :column="2" border size="small" class="desc">
          <el-descriptions-item label="deviceId" :span="2"><span class="ex-num">{{ detail.deviceId }}</span></el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="deviceStatusTag(detail.status)" size="small">{{ deviceStatusText(detail.status) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="类型">{{ deviceTypeText(detail.deviceType) }}</el-descriptions-item>
          <el-descriptions-item label="productKey" :span="2">{{ detail.productKey }}</el-descriptions-item>
          <el-descriptions-item label="电站">{{ stationName(detail.stationId, stations) || '—' }}</el-descriptions-item>
          <el-descriptions-item label="企业">{{ detail.enterpriseId ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="父设备" :span="2"><span class="ex-num">{{ detail.parentId }}</span></el-descriptions-item>
          <el-descriptions-item label="路径" :span="2">{{ detail.path }}</el-descriptions-item>
          <el-descriptions-item label="层级" :span="2"><span class="ex-num">{{ detail.level }}</span></el-descriptions-item>
          <el-descriptions-item label="固件">{{ detail.firmwareVersion ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="协议">{{ detail.protocol }}</el-descriptions-item>
          <el-descriptions-item label="MAC" :span="2">{{ detail.mac ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="IP" :span="2">{{ detail.ip ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="累计在线" :span="2">{{ onlineSeconds(detail.onlineSeconds) }}</el-descriptions-item>
          <el-descriptions-item label="最近上线" :span="2"><span class="ex-num">{{ toLocal(detail.lastOnlineTime) }}</span></el-descriptions-item>
          <el-descriptions-item label="最近下线" :span="2"><span class="ex-num">{{ toLocal(detail.lastOfflineTime) }}</span></el-descriptions-item>
          <el-descriptions-item label="创建时间" :span="2"><span class="ex-num">{{ toLocal(detail.createTime) }}</span></el-descriptions-item>
        </el-descriptions>

        <div class="ex-card cred-card">
          <div class="ex-card-head">
            <h2 class="ex-card-title">连接凭据</h2>
            <el-button size="small" type="warning" @click="regenerateSecret">重新生成</el-button>
          </div>
          <template v-if="cred">
            <p class="cred-line">
              <span class="cred-label">密钥（脱敏）</span>
              <code class="cred-mask">{{ cred.deviceSecret }}</code>
              <el-tag size="small" :type="cred.authStatus === 1 ? 'success' : 'danger'">{{ cred.authStatus === 1 ? '正常' : '吊销' }}</el-tag>
            </p>
            <el-alert v-if="plainSecret" type="warning" :closable="false" class="cred-plain">
              <template #title>
                新密钥（仅本次展示，请立即复制保存）
                <el-button link type="primary" size="small" @click="copySecret">复制</el-button>
              </template>
              <code>{{ plainSecret }}</code>
            </el-alert>
          </template>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.filter-card { padding: 14px 18px 0; }
.filter-card :deep(.el-form-item) { margin-bottom: 14px; }
.table-card { padding-bottom: 10px; }
.pager { display: flex; justify-content: flex-end; margin-top: 12px; padding: 0 18px; }
.desc { margin-bottom: 14px; }
.cred-card { padding-bottom: 14px; }
.cred-line { display: flex; align-items: center; gap: 10px; padding: 12px 18px 0; margin: 0; font-size: 13px; }
.cred-label { color: var(--ex-ink-2); flex: none; }
.cred-mask { font-family: 'Cascadia Mono', Consolas, monospace; font-size: 12px; color: var(--ex-ink); }
.cred-plain { margin: 12px 18px 0; }
.cred-plain code { font-family: 'Cascadia Mono', Consolas, monospace; font-size: 12px; word-break: break-all; }
</style>
