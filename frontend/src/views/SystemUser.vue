<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { enterpriseApi, roleApi, userApi } from '@/api/system'
import type { SysEnterprise, SysRole, SysUserVO } from '@/types/models'
import { userStatusTag, userStatusText } from '@/utils/dicts'
import { toLocal } from '@/utils/alarmFormat'
import { useAuthStore } from '@/stores/auth'
import { hasPermi } from '@/utils/permission'

const authStore = useAuthStore()
const loading = ref(false)
const list = ref<SysUserVO[]>([])
const total = ref(0)
const pageNo = ref(1)
const pageSize = ref(10)
const query = ref({ keyword: '', status: undefined as number | undefined, enterpriseId: undefined as number | undefined })
const roles = ref<SysRole[]>([])
const enterprises = ref<SysEnterprise[]>([])

async function load() {
  loading.value = true
  try {
    const data = await userApi.page({
      current: pageNo.value, size: pageSize.value,
      keyword: query.value.keyword || undefined,
      status: query.value.status,
      enterpriseId: query.value.enterpriseId,
    })
    list.value = data.records
    total.value = data.total
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : String(e)) }
  finally { loading.value = false }
}
function search() { pageNo.value = 1; void load() }
function resetQuery() { query.value = { keyword: '', status: undefined, enterpriseId: undefined }; pageNo.value = 1; void load() }

/** 超管或当前登录账号：禁用删除/启停 */
function isProtected(row: SysUserVO): boolean {
  return Number(row.userId) === 1 || row.userId === authStore.user?.userId
}

// 新增/编辑
const dialogVisible = ref(false)
const isEdit = ref(false)
const form = ref<Partial<SysUserVO> & { password?: string; roleIds?: string[] }>({})
function openCreate() { form.value = { status: 1, roleIds: [] }; isEdit.value = false; dialogVisible.value = true }
function openEdit(row: SysUserVO) { form.value = { ...row, password: '', roleIds: [...row.roleIds] }; isEdit.value = true; dialogVisible.value = true }
async function save() {
  if (!form.value.username?.trim() || !form.value.realName?.trim()) { ElMessage.warning('用户名 / 姓名 为必填'); return }
  if (!/^[a-zA-Z0-9_.-]+$/.test(form.value.username)) { ElMessage.warning('用户名仅允许字母数字 _ . -'); return }
  if (!isEdit.value && (!form.value.password || form.value.password.length < 6)) { ElMessage.warning('创建时密码必填（6~64 位）'); return }
  try {
    const body = {
      username: form.value.username, realName: form.value.realName,
      phone: form.value.phone || undefined, email: form.value.email || undefined,
      enterpriseId: form.value.enterpriseId ?? undefined,
      status: form.value.status,
      password: form.value.password || undefined,
      roleIds: form.value.roleIds && form.value.roleIds.length ? form.value.roleIds : undefined,
    }
    if (isEdit.value) await userApi.update(form.value.userId!, body)
    else await userApi.create(body)
    ElMessage.success('保存成功')
    dialogVisible.value = false
    void load()
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : String(e)) }
}

// 分配角色
const roleDialog = ref(false)
const target = ref<SysUserVO | null>(null)
const checkedRoles = ref<string[]>([])
async function openRoles(row: SysUserVO) {
  target.value = row
  roleDialog.value = true
  try {
    checkedRoles.value = await userApi.roles(row.userId)
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : String(e)) }
}
async function saveRoles() {
  if (!target.value) return
  try {
    await userApi.assignRoles(target.value.userId, checkedRoles.value)
    ElMessage.success('角色已更新')
    roleDialog.value = false
    void load()
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : String(e)) }
}

// 重置密码
const pwdDialog = ref(false)
const pwdUser = ref<SysUserVO | null>(null)
const pwdValue = ref('')
async function openPwd(row: SysUserVO) { pwdUser.value = row; pwdValue.value = ''; pwdDialog.value = true }
async function savePwd() {
  if (!pwdUser.value) return
  if (!pwdValue.value || pwdValue.value.length < 6) { ElMessage.warning('密码长度 6~64'); return }
  try {
    await userApi.resetPassword(pwdUser.value.userId, pwdValue.value)
    ElMessage.success('密码已重置')
    pwdDialog.value = false
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : String(e)) }
}

async function switchStatus(row: SysUserVO, status: number) {
  if (status === 0) {
    try { await ElMessageBox.confirm(`确定禁用用户「${row.username}」吗？禁用后该用户将无法登录。`, '提示', { type: 'warning' }) } catch { return }
  }
  try {
    await userApi.switchStatus(row.userId, status)
    ElMessage.success(status === 1 ? '已启用' : '已禁用')
    void load()
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : String(e)) }
}
async function remove(row: SysUserVO) {
  try { await ElMessageBox.confirm(`确定删除用户「${row.username}」吗？其在线会话将被吊销。`, '提示', { type: 'warning' }) } catch { return }
  try {
    await userApi.remove(row.userId)
    ElMessage.success('已删除')
    void load()
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : String(e)) }
}

onMounted(async () => {
  void load()
  try {
    const [r, e] = await Promise.all([roleApi.list(), enterpriseApi.list().catch(() => [] as SysEnterprise[])])
    roles.value = r
    enterprises.value = e
  } catch { /* 下拉加载失败不阻塞 */ }
})
</script>

<template>
  <div class="ex-page">
    <header class="ex-page-head">
      <div class="head-title">
        <h1 class="ex-title">用户管理</h1>
        <p class="ex-sub">RBAC 用户 · 分配角色决定可见菜单与操作权限</p>
      </div>
      <el-button v-if="hasPermi(authStore.permissions, 'system:user:add')" type="primary" @click="openCreate">新增用户</el-button>
    </header>

    <section class="ex-card filter-card">
      <el-form inline @submit.prevent>
        <el-form-item label="关键字">
          <el-input v-model="query.keyword" placeholder="用户名 / 姓名" clearable style="width: 200px" @keyup.enter="search" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" clearable placeholder="全部" style="width: 120px">
            <el-option v-for="i in [0, 1, 2]" :key="i" :label="userStatusText(i)" :value="i" />
          </el-select>
        </el-form-item>
        <el-form-item label="企业">
          <el-select v-model="query.enterpriseId" clearable filterable placeholder="全部" style="width: 180px">
            <el-option v-for="e in enterprises" :key="e.enterpriseId" :label="e.enterpriseName" :value="e.enterpriseId" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="search">查询</el-button>
          <el-button @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>
    </section>

    <section class="ex-card table-card">
      <el-table :data="list" v-loading="loading" size="small" empty-text="暂无用户">
        <el-table-column prop="username" label="用户名" min-width="110" />
        <el-table-column prop="realName" label="姓名" min-width="90" />
        <el-table-column prop="phone" label="电话" width="120" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="userStatusTag(row.status)" size="small">{{ userStatusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="角色" min-width="160">
          <template #default="{ row }">
            <el-tag v-for="n in row.roleNames" :key="n" size="small" effect="plain" class="role-tag">{{ n }}</el-tag>
            <span v-if="!row.roleNames?.length" class="ex-ink-3">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="enterpriseName" label="企业" min-width="110">
          <template #default="{ row }">{{ row.enterpriseName ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="最近登录" width="140">
          <template #default="{ row }"><span class="ex-num">{{ toLocal(row.lastLoginTime) }}</span></template>
        </el-table-column>
        <el-table-column label="创建时间" width="140">
          <template #default="{ row }"><span class="ex-num">{{ toLocal(row.createTime) }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="260" fixed="right">
          <template #default="{ row }">
            <el-button v-if="hasPermi(authStore.permissions, 'system:user:edit')" link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-if="hasPermi(authStore.permissions, 'system:user:role')" link type="primary" @click="openRoles(row)">分配角色</el-button>
            <el-button v-if="hasPermi(authStore.permissions, 'system:user:resetPwd')" link type="warning" @click="openPwd(row)">重置密码</el-button>
            <el-button v-if="hasPermi(authStore.permissions, 'system:user:edit') && !isProtected(row)" link :type="row.status === 1 ? 'warning' : 'success'" @click="switchStatus(row, row.status === 1 ? 0 : 1)">
              {{ row.status === 1 ? '禁用' : '启用' }}
            </el-button>
            <el-button v-if="hasPermi(authStore.permissions, 'system:user:remove') && !isProtected(row)" link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="pager">
        <el-pagination v-model:current-page="pageNo" v-model:page-size="pageSize" :total="total"
          :page-sizes="[10, 20, 50]" layout="total, sizes, prev, pager, next"
          @size-change="pageNo = 1; void load()" @current-change="load" />
      </div>
    </section>

    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑用户' : '新增用户'" width="560px">
      <el-form label-width="90px">
        <el-form-item label="用户名" required>
          <el-input v-model="form.username" placeholder="字母数字 _ . -，如 operator" :disabled="isEdit" maxlength="64" />
        </el-form-item>
        <el-form-item label="姓名" required>
          <el-input v-model="form.realName" placeholder="真实姓名" maxlength="64" />
        </el-form-item>
        <el-form-item label="所属企业">
          <el-select v-model="form.enterpriseId" clearable filterable placeholder="无" style="width: 100%">
            <el-option v-for="e in enterprises" :key="e.enterpriseId" :label="e.enterpriseName" :value="e.enterpriseId" />
          </el-select>
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="form.roleIds" multiple clearable placeholder="不分配角色" style="width: 100%">
            <el-option v-for="r in roles" :key="r.roleId" :label="r.roleName" :value="r.roleId" />
          </el-select>
        </el-form-item>
        <el-form-item label="手机">
          <el-input v-model="form.phone" maxlength="32" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="form.email" maxlength="128" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">禁用</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="密码" :required="!isEdit">
          <el-input v-model="form.password" type="password" show-password :placeholder="isEdit ? '留空则不修改' : '6~64 位'" maxlength="64" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="roleDialog" :title="`分配角色 · ${target?.username ?? ''}`" width="420px">
      <el-select v-model="checkedRoles" multiple style="width: 100%" placeholder="选择角色">
        <el-option v-for="r in roles" :key="r.roleId" :label="`${r.roleName} (${r.roleCode})`" :value="r.roleId" />
      </el-select>
      <template #footer>
        <el-button @click="roleDialog = false">取消</el-button>
        <el-button type="primary" @click="saveRoles">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="pwdDialog" :title="`重置密码 · ${pwdUser?.username ?? ''}`" width="420px">
      <el-input v-model="pwdValue" type="password" show-password placeholder="新密码 6~64 位" maxlength="64" @keyup.enter="savePwd" />
      <template #footer>
        <el-button @click="pwdDialog = false">取消</el-button>
        <el-button type="primary" @click="savePwd">重置</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.filter-card { padding: 14px 18px 0; }
.filter-card :deep(.el-form-item) { margin-bottom: 14px; }
.table-card { padding-bottom: 10px; }
.pager { display: flex; justify-content: flex-end; margin-top: 12px; padding: 0 18px; }
.role-tag { margin-right: 4px; }
.ex-ink-3 { color: var(--ex-ink-3); }
</style>
