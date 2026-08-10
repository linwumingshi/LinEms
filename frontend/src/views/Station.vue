<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { emsApi } from '@/api/ems'
import { stationApi } from '@/api/station'
import { enterpriseApi } from '@/api/system'
import type { Station, StationSaveReq, SysEnterprise } from '@/types/models'
import { GRID_TYPE_OPTIONS, stationStatusTag, stationStatusText } from '@/utils/dicts'

const router = useRouter()

/** 电站 → 是否已配置安全约束（并行探测当前页电站，供状态列展示） */
const constraintStatus = ref<Record<string, boolean>>({})

const loading = ref(false)
const list = ref<Station[]>([])
const total = ref(0)
const pageNo = ref(1)
const pageSize = ref(20)
const query = ref({ keyword: '', enterpriseId: undefined as string | undefined, status: undefined as number | undefined, gridType: '' })
const enterprises = ref<SysEnterprise[]>([])

function enterpriseName(id?: string | null): string {
  if (!id) return '—'
  return enterprises.value.find((e) => String(e.enterpriseId) === String(id))?.enterpriseName ?? '—'
}

async function load() {
  loading.value = true
  try {
    const data = await stationApi.stationPage({
      pageNum: pageNo.value, pageSize: pageSize.value,
      keyword: query.value.keyword || undefined,
      enterpriseId: query.value.enterpriseId,
      status: query.value.status,
      gridType: query.value.gridType || undefined,
    })
    list.value = data.records
    total.value = data.total
    void loadConstraintStatus()
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : String(e)) }
  finally { loading.value = false }
}

/** 并行探测当前页电站约束是否存在（有约束接口成功 / 无约束业务错误） */
async function loadConstraintStatus(): Promise<void> {
  const ids = list.value.map((s) => String(s.stationId))
  if (ids.length === 0) return
  const results = await Promise.allSettled(ids.map((id) => emsApi.constraintGet(id)))
  const map: Record<string, boolean> = {}
  ids.forEach((id, i) => { map[id] = results[i].status === 'fulfilled' })
  constraintStatus.value = map
}
function search() { pageNo.value = 1; void load() }
function resetQuery() { query.value = { keyword: '', enterpriseId: undefined, status: undefined, gridType: '' }; pageNo.value = 1; void load() }

// 新增/编辑
const dialogVisible = ref(false)
const isEdit = ref(false)
const form = ref<Partial<Station>>({})
const errs = ref<Record<string, string>>({})
watch(
  () => [form.value.stationCode, form.value.stationName, form.value.enterpriseId],
  () => { errs.value = {} },
)
function openCreate() { form.value = { status: 1 }; isEdit.value = false; dialogVisible.value = true }
function openEdit(row: Station) { form.value = { ...row }; isEdit.value = true; dialogVisible.value = true }
async function save() {
  errs.value = {}
  const e: Record<string, string> = {}
  if (!form.value.stationCode?.trim()) e.stationCode = '请输入电站编码'
  if (!form.value.stationName?.trim()) e.stationName = '请输入电站名称'
  if (!form.value.enterpriseId) e.enterpriseId = '请选择所属单位'
  if (Object.keys(e).length) { errs.value = e; return }
  const body: StationSaveReq = {
    stationCode: form.value.stationCode!.trim(),
    stationName: form.value.stationName!.trim(),
    enterpriseId: form.value.enterpriseId!,
    address: form.value.address?.trim() || undefined,
    longitude: form.value.longitude ?? null,
    latitude: form.value.latitude ?? null,
    installCapacity: form.value.installCapacity ?? null,
    pcsCapacity: form.value.pcsCapacity ?? null,
    batteryCapacity: form.value.batteryCapacity ?? null,
    gridType: form.value.gridType || undefined,
    status: form.value.status ?? 1,
  }
  try {
    if (isEdit.value) await stationApi.update(form.value.stationId!, body)
    else await stationApi.create(body)
    ElMessage.success('保存成功')
    dialogVisible.value = false
    void load()
  } catch (e2) { ElMessage.error(e2 instanceof Error ? e2.message : String(e2)) }
}
async function remove(row: Station) {
  try { await ElMessageBox.confirm(`确定删除电站「${row.stationName}」吗？`, '提示', { type: 'warning' }) } catch { return }
  try {
    await stationApi.remove(row.stationId)
    ElMessage.success('已删除')
    void load()
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : String(e)) }
}

onMounted(async () => {
  void load()
  try {
    enterprises.value = await enterpriseApi.list()
  } catch { /* 下拉加载失败不阻塞 */ }
})
</script>

<template>
  <div class="ex-page">
    <header class="ex-page-head">
      <div class="head-title">
        <h1 class="ex-title">电站管理</h1>
        <p class="ex-sub">电站资产 · 归属单位 / 容量 / 电网类型 · 供策略与充放电计划下拉调用</p>
      </div>
      <el-button type="primary" @click="openCreate">新增电站</el-button>
    </header>

    <section class="ex-card filter-card">
      <el-form inline @submit.prevent>
        <el-form-item label="关键字">
          <el-input v-model="query.keyword" placeholder="名称 / 编码" clearable style="width: 200px" @keyup.enter="search" />
        </el-form-item>
        <el-form-item label="所属单位">
          <el-select v-model="query.enterpriseId" clearable filterable placeholder="全部" style="width: 180px">
            <el-option v-for="e in enterprises" :key="e.enterpriseId" :label="e.enterpriseName" :value="e.enterpriseId" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" clearable placeholder="全部" style="width: 110px">
            <el-option v-for="i in [1, 0]" :key="i" :label="stationStatusText(i)" :value="i" />
          </el-select>
        </el-form-item>
        <el-form-item label="电网类型">
          <el-select v-model="query.gridType" clearable placeholder="全部" style="width: 130px">
            <el-option v-for="t in GRID_TYPE_OPTIONS" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="search">查询</el-button>
          <el-button @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>
    </section>

    <section class="ex-card table-card">
      <el-table :data="list" v-loading="loading" size="small" empty-text="暂无电站，点击右上角新增">
        <el-table-column prop="stationCode" label="电站编码" min-width="120" show-overflow-tooltip />
        <el-table-column prop="stationName" label="电站名称" min-width="140" show-overflow-tooltip />
        <el-table-column label="所属单位" min-width="130">
          <template #default="{ row }">{{ enterpriseName(row.enterpriseId) }}</template>
        </el-table-column>
        <el-table-column label="装机容量 kWh" width="120">
          <template #default="{ row }"><span class="ex-num">{{ row.installCapacity ?? '—' }}</span></template>
        </el-table-column>
        <el-table-column label="PCS 容量 kW" width="110">
          <template #default="{ row }"><span class="ex-num">{{ row.pcsCapacity ?? '—' }}</span></template>
        </el-table-column>
        <el-table-column label="电池容量 kWh" width="120">
          <template #default="{ row }"><span class="ex-num">{{ row.batteryCapacity ?? '—' }}</span></template>
        </el-table-column>
        <el-table-column prop="gridType" label="电网类型" width="100">
          <template #default="{ row }">{{ row.gridType ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="stationStatusTag(row.status)" size="small">{{ stationStatusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="安全约束" width="90" align="center">
          <template #default="{ row }">
            <el-tag v-if="constraintStatus[String(row.stationId)]" type="success" size="small">已配置</el-tag>
            <el-tag v-else type="warning" size="small">未配置</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="primary" @click="router.push(`/ems/constraint?station=${row.stationId}`)">安全约束</el-button>
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

    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑电站' : '新增电站'" width="560px">
      <el-form label-width="110px">
        <el-form-item label="电站编码" required :error="errs.stationCode">
          <el-input v-model="form.stationCode" placeholder="电站编码，如 ST001（唯一，删除后不可复用）" maxlength="64" />
        </el-form-item>
        <el-form-item label="电站名称" required :error="errs.stationName">
          <el-input v-model="form.stationName" placeholder="电站名称" maxlength="128" />
        </el-form-item>
        <el-form-item label="所属单位" required :error="errs.enterpriseId">
          <el-select v-model="form.enterpriseId" filterable placeholder="选择单位" style="width: 100%">
            <el-option v-for="e in enterprises" :key="e.enterpriseId" :label="e.enterpriseName" :value="e.enterpriseId" />
          </el-select>
        </el-form-item>
        <el-form-item label="地址">
          <el-input v-model="form.address" maxlength="256" />
        </el-form-item>
        <el-form-item label="经度">
          <el-input-number v-model="form.longitude" :precision="6" controls-position="right" style="width: 100%" />
        </el-form-item>
        <el-form-item label="纬度">
          <el-input-number v-model="form.latitude" :precision="6" controls-position="right" style="width: 100%" />
        </el-form-item>
        <el-form-item label="装机容量 kWh">
          <el-input-number v-model="form.installCapacity" :precision="2" :min="0" controls-position="right" style="width: 100%" />
        </el-form-item>
        <el-form-item label="PCS 容量 kW">
          <el-input-number v-model="form.pcsCapacity" :precision="2" :min="0" controls-position="right" style="width: 100%" />
        </el-form-item>
        <el-form-item label="电池容量 kWh">
          <el-input-number v-model="form.batteryCapacity" :precision="2" :min="0" controls-position="right" style="width: 100%" />
        </el-form-item>
        <el-form-item label="电网类型">
          <el-select v-model="form.gridType" clearable placeholder="请选择" style="width: 100%">
            <el-option v-for="t in GRID_TYPE_OPTIONS" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio :value="1">运行</el-radio>
            <el-radio :value="0">停运</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.filter-card { padding: 14px 18px 0; }
.filter-card :deep(.el-form-item) { margin-bottom: 14px; }
.table-card { padding-bottom: 10px; }
.pager { display: flex; justify-content: flex-end; margin-top: 12px; padding: 0 18px; }
</style>
