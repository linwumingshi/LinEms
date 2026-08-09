<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { enterpriseApi } from '@/api/system'
import type { SysEnterprise } from '@/types/models'
import { productStatusText } from '@/utils/dicts'
import { toLocal } from '@/utils/alarmFormat'
import { collectSubtreeIds, findEnterpriseNode, flatEnterpriseTree } from '@/utils/enterpriseTree'
import { useAuthStore } from '@/stores/auth'
import { hasPermi } from '@/utils/permission'

const authStore = useAuthStore()
const loading = ref(false)
const tree = ref<SysEnterprise[]>([])
const selectedId = ref<string | null>(null)

/** 当前选中节点（树 reload 后按 id 重新解析，避免绑定陈旧引用） */
const current = computed<SysEnterprise | null>(() =>
  selectedId.value ? findEnterpriseNode(tree.value, selectedId.value) : null,
)

async function loadTree() {
  loading.value = true
  try {
    tree.value = await enterpriseApi.tree()
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : String(e)) }
  finally { loading.value = false }
}
function selectNode(data: SysEnterprise) { selectedId.value = data.enterpriseId }

function parentName(node: SysEnterprise): string {
  if (!node.parentId || node.parentId === '0') return '顶级'
  return findEnterpriseNode(tree.value, node.parentId)?.enterpriseName ?? '—'
}

// —— 新增/编辑 ——
const dialogVisible = ref(false)
const isEdit = ref(false)
const addChild = ref(false)
const form = ref<{ enterpriseCode: string; enterpriseName: string; parentId?: string; sort?: number; status: number }>(
  { enterpriseCode: '', enterpriseName: '', sort: 0, status: 1 },
)
const errs = ref<Record<string, string>>({})
watch(
  () => [form.value.enterpriseCode, form.value.enterpriseName],
  () => { errs.value = {} },
)
const dialogTitle = computed(() => (isEdit.value ? '编辑单位' : addChild.value ? '新增子单位' : '新增根单位'))
const parentLabel = computed(() => current.value?.enterpriseName ?? '')
/** 编辑时上级可选项：排除自身及其子树（后端环校验兜底） */
const parentOptions = computed<SysEnterprise[]>(() => {
  const all = flatEnterpriseTree(tree.value)
  if (!current.value) return all
  const exclude = collectSubtreeIds(current.value)
  return all.filter((n) => !exclude.has(String(n.enterpriseId)))
})

function openCreateRoot() {
  form.value = { enterpriseCode: '', enterpriseName: '', sort: 0, status: 1 }
  isEdit.value = false; addChild.value = false; dialogVisible.value = true
}
function openCreateChild() {
  if (!current.value) return
  form.value = { enterpriseCode: '', enterpriseName: '', sort: 0, status: 1, parentId: current.value.enterpriseId }
  isEdit.value = false; addChild.value = true; dialogVisible.value = true
}
function openEdit() {
  if (!current.value) return
  form.value = {
    enterpriseCode: current.value.enterpriseCode,
    enterpriseName: current.value.enterpriseName,
    parentId: current.value.parentId || undefined,
    sort: current.value.sort,
    status: current.value.status,
  }
  isEdit.value = true; addChild.value = false; dialogVisible.value = true
}
async function save() {
  errs.value = {}
  const e: Record<string, string> = {}
  if (!form.value.enterpriseCode?.trim()) e.enterpriseCode = '请输入单位编码'
  if (!form.value.enterpriseName?.trim()) e.enterpriseName = '请输入单位名称'
  if (Object.keys(e).length) { errs.value = e; return }
  const body = {
    enterpriseCode: form.value.enterpriseCode.trim(),
    enterpriseName: form.value.enterpriseName.trim(),
    parentId: form.value.parentId || undefined,
    sort: form.value.sort,
    status: form.value.status,
  }
  try {
    let savedId = ''
    if (isEdit.value) {
      await enterpriseApi.update(current.value!.enterpriseId, body)
      savedId = current.value!.enterpriseId
    } else {
      savedId = await enterpriseApi.create(body)
    }
    ElMessage.success('保存成功')
    dialogVisible.value = false
    await loadTree()
    selectedId.value = savedId
  } catch (e2) { ElMessage.error(e2 instanceof Error ? e2.message : String(e2)) }
}
async function toggleStatus() {
  if (!current.value) return
  const next = current.value.status === 1 ? 0 : 1
  try {
    await enterpriseApi.switchStatus(current.value.enterpriseId, next)
    ElMessage.success(next === 1 ? '已启用' : '已停用')
    await loadTree()
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : String(e)) }
}
async function remove() {
  if (!current.value) return
  if (current.value.children?.length) { ElMessage.warning('存在子单位，请先删除子单位'); return }
  try { await ElMessageBox.confirm(`确定删除单位「${current.value.enterpriseName}」吗？`, '提示', { type: 'warning' }) } catch { return }
  try {
    await enterpriseApi.remove(current.value.enterpriseId)
    ElMessage.success('已删除')
    selectedId.value = null
    await loadTree()
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : String(e)) }
}

onMounted(() => { void loadTree() })
</script>

<template>
  <div class="ex-page">
    <header class="ex-page-head">
      <div class="head-title">
        <h1 class="ex-title">单位管理</h1>
        <p class="ex-sub">单位组织树 · 电站与用户归属的上级单位</p>
      </div>
      <el-button v-if="hasPermi(authStore.permissions, 'system:enterprise:add')" type="primary" @click="openCreateRoot">新增根单位</el-button>
    </header>

    <section class="ex-card org-card">
      <div class="org-tree">
        <div class="org-tree-head">单位树</div>
        <el-tree
          :data="tree"
          :props="{ label: 'enterpriseName', children: 'children' }"
          node-key="enterpriseId"
          highlight-current
          default-expand-all
          :expand-on-click-node="false"
          :current-node-key="selectedId"
          v-loading="loading"
          @node-click="selectNode"
        />
      </div>
      <div class="org-detail">
        <template v-if="current">
          <div class="detail-head">
            <h2 class="detail-title">{{ current.enterpriseName }}</h2>
            <div class="detail-actions">
              <el-button v-if="hasPermi(authStore.permissions, 'system:enterprise:add')" link type="primary" @click="openCreateChild">新增子单位</el-button>
              <el-button v-if="hasPermi(authStore.permissions, 'system:enterprise:edit')" link type="primary" @click="openEdit">编辑</el-button>
              <el-button v-if="hasPermi(authStore.permissions, 'system:enterprise:edit')" link :type="current.status === 1 ? 'warning' : 'success'" @click="toggleStatus">
                {{ current.status === 1 ? '停用' : '启用' }}
              </el-button>
              <el-button v-if="hasPermi(authStore.permissions, 'system:enterprise:remove')" link type="danger" @click="remove">删除</el-button>
            </div>
          </div>
          <el-descriptions :column="1" border size="small" class="detail-desc">
            <el-descriptions-item label="单位编码">{{ current.enterpriseCode }}</el-descriptions-item>
            <el-descriptions-item label="单位名称">{{ current.enterpriseName }}</el-descriptions-item>
            <el-descriptions-item label="上级单位">{{ parentName(current) }}</el-descriptions-item>
            <el-descriptions-item label="排序">{{ current.sort }}</el-descriptions-item>
            <el-descriptions-item label="状态">
              <el-tag size="small" :type="current.status === 1 ? 'success' : 'info'">{{ productStatusText(current.status) }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="创建时间"><span class="ex-num">{{ toLocal(current.createTime) }}</span></el-descriptions-item>
          </el-descriptions>
        </template>
        <div v-else class="detail-empty">请选择左侧单位</div>
      </div>
    </section>

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="480px">
      <el-form label-width="90px">
        <el-form-item v-if="addChild" label="上级单位">
          <el-input :model-value="parentLabel" disabled />
        </el-form-item>
        <el-form-item v-if="isEdit" label="上级单位">
          <el-select v-model="form.parentId" clearable filterable placeholder="无（顶级）" style="width: 100%">
            <el-option v-for="n in parentOptions" :key="n.enterpriseId" :label="n.enterpriseName" :value="n.enterpriseId" />
          </el-select>
        </el-form-item>
        <el-form-item label="单位编码" required :error="errs.enterpriseCode">
          <el-input v-model="form.enterpriseCode" placeholder="单位编码，如 ENT001" maxlength="64" />
        </el-form-item>
        <el-form-item label="单位名称" required :error="errs.enterpriseName">
          <el-input v-model="form.enterpriseName" placeholder="单位名称" maxlength="128" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sort" :min="0" :max="9999" controls-position="right" style="width: 100%" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">停用</el-radio>
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
.org-card { display: flex; padding: 0; min-height: 480px; }
.org-tree { width: 260px; border-right: 1px solid var(--ex-hair-soft); padding: 14px; overflow-y: auto; }
.org-tree-head { font-size: 12px; color: var(--ex-ink-3); margin-bottom: 8px; }
.org-detail { flex: 1; padding: 18px 20px; }
.detail-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; }
.detail-title { font-size: 16px; font-weight: 600; color: var(--ex-ink); margin: 0; }
.detail-actions { display: flex; gap: 4px; }
.detail-desc { max-width: 460px; }
.detail-empty { color: var(--ex-ink-3); padding: 80px 0; text-align: center; }
</style>
