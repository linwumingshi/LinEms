<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { permApi } from '@/api/system'
import type { SysPermission, SysPermissionSaveReq } from '@/types/models'
import { permTypeText, resourceTypeText } from '@/utils/dicts'
import { PermissionStatus, PermType, PERMISSION_STATUS_TEXT } from '@/utils/enums'
import { useAuthStore } from '@/stores/auth'
import { hasPermi } from '@/utils/permission'

const authStore = useAuthStore()
const loading = ref(false)
const tree = ref<SysPermission[]>([])

async function load() {
  loading.value = true
  try {
    tree.value = await permApi.tree()
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : String(e)) }
  finally { loading.value = false }
}

// 新增/编辑
const dialogVisible = ref(false)
const isEdit = ref(false)
const form = ref<Partial<SysPermission>>({})
const errs = ref<{ permCode?: string; permName?: string }>({})
watch(() => [form.value.permCode, form.value.permName], () => { errs.value = {} })
function openCreate(parent?: SysPermission) {
  form.value = { parentId: parent?.permId ?? '0', permType: PermType.MENU, status: PermissionStatus.NORMAL, visible: 0, sort: 0 }
  isEdit.value = false
  dialogVisible.value = true
}
function openEdit(row: SysPermission) { form.value = { ...row }; isEdit.value = true; dialogVisible.value = true }
async function save() {
  const e: { permCode?: string; permName?: string } = {}
  if (!form.value.permCode?.trim()) e.permCode = '请输入权限编码'
  if (!form.value.permName?.trim()) e.permName = '请输入权限名称'
  if (Object.keys(e).length) { errs.value = e; return }
  try {
    if (isEdit.value) await permApi.update(form.value.permId!, form.value as SysPermissionSaveReq)
    else await permApi.create(form.value as SysPermissionSaveReq)
    ElMessage.success('保存成功')
    dialogVisible.value = false
    void load()
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : String(e)) }
}

async function switchStatus(row: SysPermission, status: PermissionStatus) {
  try {
    await permApi.switchStatus(row.permId, status)
    ElMessage.success(status === PermissionStatus.NORMAL ? '已启用' : '已停用')
    void load()
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : String(e)) }
}
async function remove(row: SysPermission) {
  if (row.children?.length) { ElMessage.warning('存在子节点，请先删除子节点'); return }
  try { await ElMessageBox.confirm(`确定删除权限「${row.permName}」吗？`, '提示', { type: 'warning' }) } catch { return }
  try {
    await permApi.remove(row.permId)
    ElMessage.success('已删除')
    void load()
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : String(e)) }
}

onMounted(load)
</script>

<template>
  <div class="ex-page">
    <header class="ex-page-head">
      <div class="head-title">
        <h1 class="ex-title">菜单权限</h1>
        <p class="ex-sub">菜单 / 按钮 / 数据权限树 · 决定侧边栏可见性与操作按钮</p>
      </div>
      <el-button v-if="hasPermi(authStore.permissions, 'system:perm:add')" type="primary" @click="openCreate()">新增权限</el-button>
    </header>

    <section class="ex-card table-card">
      <el-table :data="tree" v-loading="loading" size="small" row-key="permId"
        :tree-props="{ children: 'children' }" default-expand-all empty-text="暂无权限数据">
        <el-table-column prop="permName" label="名称" min-width="180" show-overflow-tooltip />
        <el-table-column prop="permCode" label="权限编码" min-width="160" show-overflow-tooltip>
          <template #default="{ row }"><code class="perm-code">{{ row.permCode }}</code></template>
        </el-table-column>
        <el-table-column label="类型" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.permType === PermType.MENU ? 'primary' : row.permType === PermType.BUTTON ? 'success' : 'info'">{{ permTypeText(row.permType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="path" label="路由" min-width="130" show-overflow-tooltip>
          <template #default="{ row }">{{ row.path ?? '—' }}</template>
        </el-table-column>
        <el-table-column prop="icon" label="图标" width="80">
          <template #default="{ row }">{{ row.icon ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="可见" width="70">
          <template #default="{ row }">{{ row.visible === 0 ? '显示' : '隐藏' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === PermissionStatus.NORMAL ? 'success' : 'danger'" size="small">{{ PERMISSION_STATUS_TEXT[row.status as PermissionStatus] }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="sort" label="排序" width="70">
          <template #default="{ row }"><span class="ex-num">{{ row.sort }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button v-if="hasPermi(authStore.permissions, 'system:perm:add')" link type="primary" @click="openCreate(row)">新增子</el-button>
            <el-button v-if="hasPermi(authStore.permissions, 'system:perm:edit')" link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-if="hasPermi(authStore.permissions, 'system:perm:edit')" link :type="row.status === PermissionStatus.NORMAL ? 'warning' : 'success'" @click="switchStatus(row, row.status === PermissionStatus.NORMAL ? PermissionStatus.DISABLED : PermissionStatus.NORMAL)">
              {{ row.status === PermissionStatus.NORMAL ? '停用' : '启用' }}
            </el-button>
            <el-button v-if="hasPermi(authStore.permissions, 'system:perm:remove')" link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑权限' : '新增权限'" width="560px">
      <el-form label-width="90px">
        <el-form-item label="父级">
          <el-tree-select v-model="form.parentId" :data="[{ permId: 0, permName: '顶级', children: tree }]"
            node-key="permId" :props="{ label: 'permName', children: 'children' }" check-strictly
            default-expand-all clearable style="width: 100%" placeholder="顶级" />
        </el-form-item>
        <el-form-item label="类型">
          <el-radio-group v-model="form.permType">
            <el-radio :value="PermType.MENU">菜单</el-radio>
            <el-radio :value="PermType.BUTTON">按钮</el-radio>
            <el-radio :value="PermType.DATA">数据</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="权限编码" required :error="errs.permCode">
          <el-input v-model="form.permCode" placeholder="如 system:user:add（全局唯一）" maxlength="100" />
        </el-form-item>
        <el-form-item label="权限名称" required :error="errs.permName">
          <el-input v-model="form.permName" placeholder="如 用户新增" maxlength="64" />
        </el-form-item>
        <el-form-item>
          <template #label>
            <span>资源类型</span>
            <el-tooltip content="数据权限预留字段：当前鉴权按权限编码判断，此字段暂不生效" placement="top">
              <span class="res-tip">ⓘ</span>
            </el-tooltip>
          </template>
          <el-select v-model="form.resourceType" clearable placeholder="不限" style="width: 100%">
            <el-option v-for="t in ['DEVICE', 'STRATEGY', 'ALARM', 'STATION']" :key="t" :label="`${resourceTypeText(t)} (${t})`" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.permType === PermType.MENU" label="路由">
          <el-input v-model="form.path" placeholder="如 /system/user" maxlength="200" />
        </el-form-item>
        <el-form-item v-if="form.permType === PermType.MENU" label="图标">
          <el-input v-model="form.icon" placeholder="如 Setting" maxlength="64" />
        </el-form-item>
        <el-form-item v-if="form.permType === PermType.MENU" label="组件">
          <el-input v-model="form.component" placeholder="如 system/user/index" maxlength="128" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sort" :min="0" style="width: 160px" />
        </el-form-item>
        <el-form-item label="可见">
          <el-radio-group v-model="form.visible">
            <el-radio :value="0">显示</el-radio>
            <el-radio :value="1">隐藏</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio :value="PermissionStatus.NORMAL">正常</el-radio>
            <el-radio :value="PermissionStatus.DISABLED">停用</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="256" />
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
.table-card { padding-bottom: 10px; }
.perm-code { font-family: 'Cascadia Mono', Consolas, monospace; font-size: 12px; color: var(--ex-ink-2); }
.res-tip {
  cursor: help;
  margin-left: 4px;
  font-size: 12px;
}
</style>
