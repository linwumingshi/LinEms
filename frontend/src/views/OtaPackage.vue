<template>
  <div class="page">
    <el-card shadow="never" class="toolbar-card">
      <div class="toolbar">
        <el-select v-model="query.productKey" placeholder="产品" filterable clearable style="width: 200px" @change="load">
          <el-option v-for="p in productOptions" :key="p.productKey" :label="`${p.productName} (${p.productKey})`" :value="p.productKey" />
        </el-select>
        <el-input v-model="query.version" placeholder="版本号" clearable style="width: 140px" @keyup.enter="load" />
        <el-button type="primary" @click="load">查询</el-button>
        <el-button type="success" @click="uploadVisible = true">上传升级包</el-button>
      </div>
    </el-card>

    <el-card shadow="never">
      <el-table :data="rows" v-loading="loading" stripe>
        <el-table-column prop="productKey" label="产品" width="130" />
        <el-table-column prop="version" label="版本" width="90" />
        <el-table-column prop="module" label="模块" width="80" />
        <el-table-column label="类型" width="80">
          <template #default="{ row }">
            <el-tag :type="row.packageType === 2 ? 'warning' : 'primary'" size="small">
              {{ row.packageType === 2 ? '差分' : '全量' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="baseVersion" label="差分源版本" width="100">
          <template #default="{ row }">{{ row.baseVersion || '-' }}</template>
        </el-table-column>
        <el-table-column prop="fileName" label="文件名" min-width="160" show-overflow-tooltip />
        <el-table-column label="大小" width="90">
          <template #default="{ row }">{{ fmtSize(row.fileSize) }}</template>
        </el-table-column>
        <el-table-column label="摘要" min-width="150">
          <template #default="{ row }">
            <el-tooltip :content="`MD5: ${row.md5}\nSHA256: ${row.sha256}`" placement="top">
              <span class="mono">{{ row.sha256.slice(0, 12) }}…</span>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
              {{ row.status === 1 ? '正常' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="165" />
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="download(row)">下载</el-button>
            <el-button link :type="row.status === 1 ? 'warning' : 'success'" @click="toggleStatus(row)">
              {{ row.status === 1 ? '停用' : '启用' }}
            </el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
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

    <!-- 上传对话框 -->
    <el-dialog v-model="uploadVisible" title="上传升级包" width="520px" destroy-on-close>
      <el-form label-width="110px">
        <el-form-item label="固件文件" required>
          <el-upload
            :auto-upload="false"
            :limit="1"
            accept=".bin,.fw,.img"
            :on-change="onFileChange"
            :on-remove="() => (file = null)"
          >
            <el-button>选择文件</el-button>
          </el-upload>
          <div class="tip" v-if="file">已选：{{ file.name }}（{{ fmtSize(file.size) }}）</div>
        </el-form-item>
        <el-form-item label="产品" required>
          <el-select v-model="form.productKey" filterable placeholder="选择产品" style="width: 100%">
            <el-option v-for="p in productOptions" :key="p.productKey" :label="`${p.productName} (${p.productKey})`" :value="p.productKey" />
          </el-select>
        </el-form-item>
        <el-form-item label="版本号" required>
          <el-input v-model="form.version" placeholder="如 1.0.0" />
        </el-form-item>
        <el-form-item label="模块">
          <el-input v-model="form.module" placeholder="默认 main" />
        </el-form-item>
        <el-form-item label="包类型">
          <el-radio-group v-model="form.packageType">
            <el-radio :value="1">全量包</el-radio>
            <el-radio :value="2">差分包</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="差分源版本" v-if="form.packageType === 2">
          <el-input v-model="form.baseVersion" placeholder="差分前的源版本" />
        </el-form-item>
        <el-form-item label="升级说明">
          <el-input v-model="form.description" type="textarea" :rows="2" placeholder="变更日志/说明" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="uploadVisible = false">取消</el-button>
        <el-button type="primary" :loading="uploading" @click="submitUpload">上传</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { otaApi } from '@/api/ota'
import { productApi } from '@/api/product'
import { getToken } from '@/utils/auth-storage'
import type { OtaPackage, OtaPackageSaveReq, Product } from '@/types/models'

const loading = ref(false)
const uploading = ref(false)
const uploadVisible = ref(false)
const rows = ref<OtaPackage[]>([])
const total = ref(0)
const file = ref<File | null>(null)
/** 产品下拉选项（来自产品服务，网关 /api/product/page） */
const productOptions = ref<Product[]>([])

const query = reactive({ productKey: '', version: '', pageNum: 1, pageSize: 10 })
const form = reactive<OtaPackageSaveReq & { packageType: number }>({
  productKey: '',
  version: '',
  module: 'main',
  packageType: 1,
  baseVersion: '',
  description: '',
})

function fmtSize(n: number): string {
  if (!n) return '0 B'
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  return `${(n / 1024 / 1024).toFixed(2)} MB`
}

async function load() {
  loading.value = true
  try {
    const data = await otaApi.packages(query)
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

async function loadProducts() {
  try {
    const data = await productApi.page({ pageSize: 200 })
    productOptions.value = data.records ?? []
  }
  catch { /* 静默：产品下拉加载失败不阻断主流程 */ }
}

function onFileChange(up: { raw: File }) {
  file.value = up.raw
}

async function submitUpload() {
  if (!file.value) return ElMessage.warning('请选择固件文件')
  if (!form.productKey) return ElMessage.warning('请选择产品')
  if (!form.version) return ElMessage.warning('请填写版本号')
  uploading.value = true
  try {
    await otaApi.uploadPackage(file.value, form)
    ElMessage.success('上传成功')
    uploadVisible.value = false
    file.value = null
    load()
  }
  catch (e) {
    ElMessage.error((e as Error).message)
  }
  finally {
    uploading.value = false
  }
}

function download(row: OtaPackage) {
  // 下载走网关，需带 token（axios 无拦截器时直接 window.open 会 401，用 blob 拉取）
  void downloadWithAuth(row)
}

async function downloadWithAuth(row: OtaPackage) {
  try {
    const resp = await fetch(otaApi.downloadUrl(row), {
      headers: { Authorization: `Bearer ${getToken() || ''}` },
    })
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`)
    const blob = await resp.blob()
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = row.fileName
    a.click()
    URL.revokeObjectURL(url)
  }
  catch (e) {
    ElMessage.error('下载失败: ' + (e as Error).message)
  }
}

async function toggleStatus(row: OtaPackage) {
  const target = row.status === 1 ? 2 : 1
  try {
    await otaApi.updatePackageStatus(row.packageId, target)
    ElMessage.success(target === 1 ? '已启用' : '已停用')
    load()
  }
  catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function remove(row: OtaPackage) {
  try {
    await ElMessageBox.confirm(`确认删除升级包 ${row.productKey}/${row.version}？`, '删除确认', { type: 'warning' })
    await otaApi.deletePackage(row.packageId)
    ElMessage.success('已删除')
    load()
  }
  catch (e) {
    if ((e as { message?: string })?.message !== 'cancel') ElMessage.error((e as Error).message)
  }
}

onMounted(() => { load(); loadProducts() })
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
.mono {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 4px;
}
</style>
