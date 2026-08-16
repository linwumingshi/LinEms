<template>
  <div class="page">
    <el-card shadow="never" class="toolbar-card">
      <div class="toolbar">
        <el-input v-model="query.taskName" placeholder="任务名称" clearable style="width: 180px" @keyup.enter="load" />
        <el-select v-model="query.status" placeholder="状态" clearable style="width: 130px" @change="load">
          <el-option v-for="(label, key) in TASK_STATUS" :key="key" :label="label" :value="Number(key)" />
        </el-select>
        <el-button type="primary" @click="load">查询</el-button>
        <el-button type="success" @click="openCreate">创建任务</el-button>
      </div>
    </el-card>

    <el-card shadow="never">
      <el-table :data="rows" v-loading="loading" stripe>
        <el-table-column prop="taskName" label="任务名称" min-width="150" show-overflow-tooltip />
        <el-table-column label="类型" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.taskType === 3 ? 'warning' : 'primary'">
              {{ row.taskType === 1 ? '全部设备' : row.taskType === 2 ? '指定设备' : '灰度' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="灰度比例" width="80">
          <template #default="{ row }">{{ row.grayRatio != null ? row.grayRatio + '%' : '-' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="statusType(row.status)">{{ TASK_STATUS[row.status] || row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="设备" width="110">
          <template #default="{ row }">
            <span class="stat">{{ row.successCount }}/{{ row.deviceCount }}</span>
            <span v-if="row.failCount" class="stat-fail">(败{{ row.failCount }})</span>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="165" />
        <el-table-column label="操作" width="280" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">明细</el-button>
            <el-button v-if="row.taskType === 3 && row.status === 1" link type="success" @click="advance(row)">灰度推进</el-button>
            <el-button v-if="row.status === 1" link type="warning" @click="pause(row)">暂停</el-button>
            <el-button v-if="row.status === 3" link type="success" @click="resume(row)">恢复</el-button>
            <el-button v-if="row.status === 0 || row.status === 1" link type="danger" @click="cancel(row)">取消</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize"
        :total="total"
        layout="total, prev, pager, next, sizes"
        :page-sizes="[10, 20, 50]"
        style="margin-top: 12px; justify-content: flex-end"
        @change="load"
      />
    </el-card>

    <!-- 创建任务对话框 -->
    <el-dialog v-model="createVisible" title="创建升级任务" width="560px" destroy-on-close>
      <el-form label-width="110px">
        <el-form-item label="升级包" required>
          <el-select v-model="createForm.packageId" placeholder="选择升级包" filterable style="width: 100%" @change="onPickPackage">
            <el-option v-for="p in pkgOptions" :key="p.packageId" :label="`${p.productKey} / ${p.version} (${p.packageType === 2 ? '差分' : '全量'})`" :value="p.packageId" />
          </el-select>
        </el-form-item>
        <el-form-item label="任务名称">
          <el-input v-model="createForm.taskName" placeholder="缺省 OTA-{version}" />
        </el-form-item>
        <el-form-item label="任务类型" required>
          <el-radio-group v-model="createForm.taskType">
            <el-radio :value="1">全部设备</el-radio>
            <el-radio :value="2">指定设备</el-radio>
            <el-radio :value="3">灰度比例</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="灰度比例" v-if="createForm.taskType === 3">
          <el-slider v-model="createForm.grayRatio" :min="1" :max="100" show-input />
          <div class="tip">按 1% → 10% → 50% → 100% 档位推进，成功率 &lt;95% 自动暂停</div>
        </el-form-item>
        <el-form-item label="指定设备" v-if="createForm.taskType === 2">
          <el-input v-model="deviceIdsText" type="textarea" :rows="2" placeholder="设备 ID 列表，逗号分隔" />
        </el-form-item>
        <el-form-item label="下载策略">
          <el-radio-group v-model="createForm.downloadPolicy">
            <el-radio :value="1">差分优先</el-radio>
            <el-radio :value="2">仅全量</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="失败自动暂停">
          <el-switch v-model="createForm.autoPauseOnFail" :active-value="1" :inactive-value="0" />
        </el-form-item>
        <el-form-item label="重试次数">
          <el-input-number v-model="createForm.retryTimes" :min="0" :max="10" />
        </el-form-item>
        <el-form-item label="重试间隔(分)">
          <el-input-number v-model="createForm.retryIntervalMin" :min="1" :max="120" />
        </el-form-item>
        <el-form-item label="下载超时(分)">
          <el-input-number v-model="createForm.downloadTimeoutMin" :min="1" :max="720" />
        </el-form-item>
        <el-form-item label="升级超时(分)">
          <el-input-number v-model="createForm.upgradeTimeoutMin" :min="1" :max="360" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">创建并开始</el-button>
      </template>
    </el-dialog>

    <!-- 明细对话框 -->
    <el-dialog v-model="detailVisible" :title="`任务明细 ${detailTask?.taskName || ''}`" width="860px">
      <el-descriptions v-if="detailTask" :column="4" border size="small" class="desc">
        <el-descriptions-item label="状态">{{ TASK_STATUS[detailTask.status] || detailTask.status }}</el-descriptions-item>
        <el-descriptions-item label="设备">{{ detailTask.successCount }}/{{ detailTask.deviceCount }}</el-descriptions-item>
        <el-descriptions-item label="失败">{{ detailTask.failCount }}</el-descriptions-item>
        <el-descriptions-item label="灰度">{{ detailTask.grayRatio != null ? detailTask.grayRatio + '%' : '-' }}</el-descriptions-item>
        <el-descriptions-item label="成功率">{{ stat.successRate != null ? stat.successRate + '%' : '-' }}</el-descriptions-item>
        <el-descriptions-item label="重试">{{ detailTask.retryTimes }} 次 / {{ detailTask.retryIntervalMin }} 分</el-descriptions-item>
        <el-descriptions-item label="下载超时">{{ detailTask.downloadTimeoutMin }} 分</el-descriptions-item>
        <el-descriptions-item label="升级超时">{{ detailTask.upgradeTimeoutMin }} 分</el-descriptions-item>
      </el-descriptions>
      <el-table :data="devRows" v-loading="devLoading" stripe size="small" max-height="400">
        <el-table-column prop="deviceId" label="设备 ID" width="170">
          <template #default="{ row }"><span class="mono">{{ row.deviceId }}</span></template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="devStatusType(row.state)">{{ DEV_STATUS[row.state] || row.state }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="进度" width="90">
          <template #default="{ row }">
            <el-progress :percentage="row.progress" :stroke-width="8" />
          </template>
        </el-table-column>
        <el-table-column prop="versionBefore" label="升级前" width="90">
          <template #default="{ row }">{{ row.versionBefore || '-' }}</template>
        </el-table-column>
        <el-table-column prop="versionAfter" label="升级后" width="90">
          <template #default="{ row }">{{ row.versionAfter || '-' }}</template>
        </el-table-column>
        <el-table-column prop="retryCount" label="重试" width="60" />
        <el-table-column label="失败原因" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.failMsg || row.failCode || '-' }}</template>
        </el-table-column>
        <el-table-column prop="finishTime" label="完成时间" width="160" />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { otaApi } from '@/api/ota'
import type { OtaPackage, OtaTask, OtaTaskCreateReq, OtaTaskDevice, OtaTaskStatistics } from '@/types/models'

/** 任务状态（后端 OtaTaskService 常量） */
const TASK_STATUS: Record<number, string> = { 0: '待开始', 1: '执行中', 2: '已完成', 3: '已暂停', 4: '已取消' }
/** 设备明细状态 */
const DEV_STATUS: Record<number, string> = { 0: '待升级', 1: '下载中', 2: '升级中', 3: '成功', 4: '失败', 5: '超时', 6: '已取消' }

const loading = ref(false)
const creating = ref(false)
const devLoading = ref(false)
const createVisible = ref(false)
const detailVisible = ref(false)
const rows = ref<OtaTask[]>([])
const total = ref(0)
const pkgOptions = ref<OtaPackage[]>([])
const detailTask = ref<OtaTask | null>(null)
const devRows = ref<OtaTaskDevice[]>([])
const stat = reactive<Partial<OtaTaskStatistics>>({})
const deviceIdsText = ref('')

const query = reactive({ taskName: '', status: undefined as number | undefined, pageNum: 1, pageSize: 10 })
const createForm = reactive<OtaTaskCreateReq>({
  packageId: '',
  taskName: '',
  taskType: 1,
  downloadPolicy: 1,
  grayRatio: 10,
  autoPauseOnFail: 1,
  retryTimes: 2,
  retryIntervalMin: 5,
  downloadTimeoutMin: 60,
  upgradeTimeoutMin: 30,
})

function statusType(s: number): 'success' | 'primary' | 'warning' | 'info' | 'danger' {
  return s === 2 ? 'success' : s === 1 ? 'primary' : s === 3 ? 'warning' : s === 4 ? 'info' : 'danger'
}
function devStatusType(s: number): 'success' | 'primary' | 'warning' | 'danger' | 'info' {
  return s === 3 ? 'success' : s === 1 || s === 2 ? 'primary' : s === 4 || s === 5 ? 'danger' : 'info'
}

async function load() {
  loading.value = true
  try {
    const data = await otaApi.tasks(query)
    rows.value = data.records
    total.value = data.total
  }
  catch (e) {
    ElMessage.error((e as Error).message)
  }
  finally {
    loading.value = false
  }
}

async function loadPkgOptions() {
  try {
    const data = await otaApi.packages({ pageSize: 50 })
    pkgOptions.value = data.records.filter((p) => p.status === 1)
  }
  catch { /* 静默 */ }
}

function onPickPackage() {
  const pkg = pkgOptions.value.find((p) => p.packageId === createForm.packageId)
  if (pkg && !createForm.taskName) createForm.taskName = `OTA-${pkg.version}`
}

function openCreate() {
  Object.assign(createForm, {
    packageId: '', taskName: '', taskType: 1, downloadPolicy: 1, grayRatio: 10,
    autoPauseOnFail: 1, retryTimes: 2, retryIntervalMin: 5, downloadTimeoutMin: 60, upgradeTimeoutMin: 30,
  })
  deviceIdsText.value = ''
  createVisible.value = true
}

async function submitCreate() {
  if (!createForm.packageId) return ElMessage.warning('请选择升级包')
  if (createForm.taskType === 2) {
    const ids = deviceIdsText.value.split(/[,，\s]+/).filter(Boolean)
    if (!ids.length) return ElMessage.warning('请填写指定设备 ID')
    createForm.deviceIds = ids
  }
  creating.value = true
  try {
    await otaApi.createTask(createForm)
    ElMessage.success('任务已创建并开始')
    createVisible.value = false
    load()
  }
  catch (e) {
    ElMessage.error((e as Error).message)
  }
  finally {
    creating.value = false
  }
}

async function openDetail(row: OtaTask) {
  detailTask.value = row
  detailVisible.value = true
  loadDevices(row.taskId)
  try {
    Object.assign(stat, await otaApi.taskStatistics(row.taskId))
  }
  catch { /* 静默 */ }
}

async function loadDevices(taskId: string) {
  devLoading.value = true
  try {
    const data = await otaApi.taskDevices(taskId, { pageSize: 200 })
    devRows.value = data.records
  }
  catch (e) {
    ElMessage.error((e as Error).message)
  }
  finally {
    devLoading.value = false
  }
}

async function advance(row: OtaTask) {
  try {
    const msg = await otaApi.advanceGray(row.taskId)
    ElMessage.success(msg)
    load()
  }
  catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function pause(row: OtaTask) {
  try {
    await otaApi.pauseTask(row.taskId)
    ElMessage.success('任务已暂停')
    load()
  }
  catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function resume(row: OtaTask) {
  try {
    await otaApi.resumeTask(row.taskId)
    ElMessage.success('任务已恢复')
    load()
  }
  catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function cancel(row: OtaTask) {
  try {
    await ElMessageBox.confirm(`确认取消任务「${row.taskName}」？`, '取消确认', { type: 'warning' })
    await otaApi.cancelTask(row.taskId)
    ElMessage.success('任务已取消')
    load()
  }
  catch (e) {
    if ((e as { message?: string })?.message !== 'cancel') ElMessage.error((e as Error).message)
  }
}

onMounted(() => {
  load()
  loadPkgOptions()
})
</script>

<style scoped>
.toolbar-card {
  margin-bottom: 12px;
}
.toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
}
.stat {
  color: var(--el-color-success);
  font-weight: 600;
}
.stat-fail {
  color: var(--el-color-danger);
  font-size: 12px;
  margin-left: 4px;
}
.mono {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 12px;
}
.tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.5;
}
.desc {
  margin-bottom: 12px;
}
</style>
