<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox, ElTree } from 'element-plus'
import { permApi, roleApi } from '@/api/system'
import type { SysPermission, SysRole } from '@/types/models'
import { dataScopeText, roleStatusTag, roleStatusText } from '@/utils/dicts'
import { toLocal } from '@/utils/alarmFormat'
import { useAuthStore } from '@/stores/auth'
import { hasPermi } from '@/utils/permission'

const authStore = useAuthStore()
const loading = ref(false)
const list = ref<SysRole[]>([])
const total = ref(0)
const pageNo = ref(1)
const pageSize = ref(10)
const query = ref({ keyword: '', status: undefined as number | undefined })

async function load() {
  loading.value = true
  try {
    const data = await roleApi.page({ current: pageNo.value, size: pageSize.value, keyword: query.value.keyword || undefined, status: query.value.status })
    list.value = data.records
    total.value = data.total
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : String(e)) }
  finally { loading.value = false }
}
function search() { pageNo.value = 1; void load() }
function resetQuery() { query.value = { keyword: '', status: undefined }; pageNo.value = 1; void load() }

// 新增/编辑
const dialogVisible = ref(false)
const isEdit = ref(false)
const form = ref<Partial<SysRole>>({})
function openCreate() { form.value = { status: 1, dataScope: 3 }; isEdit.value = false; dialogVisible.value = true }
function openEdit(row: SysRole) { form.value = { ...row }; isEdit.value = true; dialogVisible.value = true }
async function save() {
  if (!form.value.roleCode?.trim() || !form.value.roleName?.trim()) { ElMessage.warning('角色编码 / 名称 为必填'); return }
  if (!/^[A-Za-z][A-Za-z0-9_]*$/.test(form.value.roleCode)) { ElMessage.warning('角色编码需以字母开头，仅字母数字下划线'); return }
  try {
    if (isEdit.value) await roleApi.update(form.value.roleId!, form.value)
    else await roleApi.create(form.value)
    ElMessage.success('保存成功')
    dialogVisible.value = false
    void load()
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : String(e)) }
}

// 授权权限
const permDialog = ref(false)
const permRole = ref<SysRole | null>(null)
const permTree = ref<SysPermission[]>([])
/** el-tree 组件实例：Element Plus 官方模板 ref 模式（InstanceType<typeof ElTree>） */
const permRef = ref<InstanceType<typeof ElTree>>()
async function openPerms(row: SysRole) {
  permRole.value = row
  permDialog.value = true
  try {
    const [tree, ids] = await Promise.all([permApi.tree(), roleApi.perms(row.roleId)])
    permTree.value = tree
    // 等树渲染后回显（nextTick 确保节点已挂载）
    await new Promise((r) => setTimeout(r, 0))
    permRef.value?.setCheckedKeys(ids)
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : String(e)) }
}
async function savePerms() {
  if (!permRole.value) return
  try {
    // 仅保存全选节点（getCheckedKeys(false)）；半选父节点不落库——
    // 若把半选父节点一并存入，重开授权时 setCheckedKeys 会按父节点级联全选其全部子孙，权限被放大
    const checked = permRef.value?.getCheckedKeys(false) as string[] ?? []
    await roleApi.assignPerms(permRole.value.roleId, checked)
    ElMessage.success('权限已更新')
    permDialog.value = false
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : String(e)) }
}

async function switchStatus(row: SysRole, status: number) {
  try {
    await roleApi.switchStatus(row.roleId, status)
    ElMessage.success(status === 1 ? '已启用' : '已停用')
    void load()
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : String(e)) }
}
async function remove(row: SysRole) {
  try { await ElMessageBox.confirm(`确定删除角色「${row.roleName}」吗？已分配该角色的用户需重新授权。`, '提示', { type: 'warning' }) } catch { return }
  try {
    await roleApi.remove(row.roleId)
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
        <h1 class="ex-title">角色管理</h1>
        <p class="ex-sub">角色承载权限集合 · dataScope 决定数据可见范围</p>
      </div>
      <el-button v-if="hasPermi(authStore.permissions, 'system:role:add')" type="primary" @click="openCreate">新增角色</el-button>
    </header>

    <section class="ex-card filter-card">
      <el-form inline @submit.prevent>
        <el-form-item label="关键字">
          <el-input v-model="query.keyword" placeholder="角色编码 / 名称" clearable style="width: 220px" @keyup.enter="search" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" clearable placeholder="全部" style="width: 120px">
            <el-option v-for="i in [0, 1]" :key="i" :label="roleStatusText(i)" :value="i" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="search">查询</el-button>
          <el-button @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>
    </section>

    <section class="ex-card table-card">
      <el-table :data="list" v-loading="loading" size="small" empty-text="暂无角色">
        <el-table-column prop="roleCode" label="角色编码" min-width="130" />
        <el-table-column prop="roleName" label="角色名称" min-width="140" />
        <el-table-column label="数据范围" width="100">
          <template #default="{ row }"><el-tag size="small" effect="plain">{{ dataScopeText(row.dataScope) }}</el-tag></template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="roleStatusTag(row.status)" size="small">{{ roleStatusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="150">
          <template #default="{ row }"><span class="ex-num">{{ toLocal(row.createTime) }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <el-button v-if="hasPermi(authStore.permissions, 'system:role:edit')" link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-if="hasPermi(authStore.permissions, 'system:role:perm') && Number(row.roleId) !== 1" link type="primary" @click="openPerms(row)">授权权限</el-button>
            <el-button v-if="hasPermi(authStore.permissions, 'system:role:edit') && Number(row.roleId) !== 1" link :type="row.status === 1 ? 'warning' : 'success'" @click="switchStatus(row, row.status === 1 ? 0 : 1)">
              {{ row.status === 1 ? '停用' : '启用' }}
            </el-button>
            <el-button v-if="hasPermi(authStore.permissions, 'system:role:remove') && Number(row.roleId) !== 1" link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="pager">
        <el-pagination v-model:current-page="pageNo" v-model:page-size="pageSize" :total="total"
          :page-sizes="[10, 20, 50]" layout="total, sizes, prev, pager, next"
          @size-change="pageNo = 1; void load()" @current-change="load" />
      </div>
    </section>

    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑角色' : '新增角色'" width="480px">
      <el-form label-width="90px">
        <el-form-item label="角色编码" required>
          <el-input v-model="form.roleCode" placeholder="如 OPERATOR（字母开头）" maxlength="64" :disabled="isEdit" />
        </el-form-item>
        <el-form-item label="角色名称" required>
          <el-input v-model="form.roleName" placeholder="如 运维操作员" maxlength="64" />
        </el-form-item>
        <el-form-item label="数据范围">
          <el-select v-model="form.dataScope" style="width: 100%">
            <el-option label="本人" :value="1" />
            <el-option label="本企业" :value="2" />
            <el-option label="本租户" :value="3" />
            <el-option label="全部" :value="4" />
          </el-select>
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

    <el-dialog v-model="permDialog" :title="`授权权限 · ${permRole?.roleName ?? ''}`" width="520px">
      <el-scrollbar max-height="420px">
        <el-tree ref="permRef" :data="permTree" node-key="permId" show-checkbox default-expand-all
          :props="{ label: 'permName', children: 'children' }" />
      </el-scrollbar>
      <template #footer>
        <el-button @click="permDialog = false">取消</el-button>
        <el-button type="primary" @click="savePerms">保存</el-button>
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
