# 子项目 A 基础档案管理 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在纯前端补齐「单位管理」（左树右详情）与「电站管理」（标准表格 CRUD）两个页面，后端接口已存在零改动。

**Architecture:** 前端两页复用现有 CRUD 范式（Product/SystemUser 同构）。先补 API 封装与类型（Task 1），再建共享树工具与字典（Task 2），再写两个页面（Task 3/4），再挂路由与导航组（Task 5），最后浏览器冒烟（Task 6）。

**Tech Stack:** Vue 3 `<script setup>` + Element Plus 2.9 + vue-router 4 + vitest 2 + vue-tsc 2。

**Spec:** `docs/superpowers/specs/2026-08-09-basic-archive-design.md`

## Global Constraints

- **11-b 内联校验**：必填红星（el-form-item `required`），错误用 `:error` 红字，不用系统 toast；域名前置条件（如单位有子节点）→ `ElMessage.warning`；后端业务错误 → 页面 `catch` 后 `ElMessage.error(e instanceof Error ? e.message : String(e))`（全局拦截器只负责归一化 Error 与 401）。
- **门控**：`hasPermi(authStore.permissions, 'system:enterprise:add|edit|remove')`；电站页不做按钮门控（与后端一致）。
- **布局令牌**：`ex-page` / `ex-page-head` / `ex-title` / `ex-sub` / `ex-card` / `filter-card` / `table-card` / `pager` / `ex-num`。
- **id 均为 string**（雪花/自增 Long → 字符串约定）；`parentId` 顶级传 `undefined`（不传 `'0'`）。
- **errs 清空 watcher 必须用单 getter 数组形式**：`watch(() => [form.value.a, form.value.b], () => { errs.value = {} })`（禁止 getter 函数数组形式）。
- **提交红线**：不 `git add -A`；`backend/energy-mqtt-broker/.../BrokerProperties.java` 与 `frontend/vite.config.ts` 永不提交。提交用显式 pathspec（如 `git add :/frontend/src/views/Enterprise.vue`）。
- **命令**：`npx vue-tsc --noEmit` 须 EXIT 0；`npm test`（vitest run）须全绿（基线 86 tests）。

---

### Task 1: 类型与 API 封装

**Files:**
- Modify: `frontend/src/types/models.ts`（Station 补字段 + 新增 SysEnterpriseSaveReq / StationSaveReq）
- Modify: `frontend/src/api/system.ts`（enterpriseApi 补全）
- Modify: `frontend/src/api/station.ts`（stationApi 补全）

**Interfaces:**
- Consumes: 无。
- Produces: `enterpriseApi.{tree,detail,create,update,remove,switchStatus}`、`stationApi.{create,detail,update,remove}`、`SysEnterpriseSaveReq`、`StationSaveReq`、`Station` 扩展字段。Task 3/4/6 依赖。

- [ ] **Step 1: 扩展 models.ts 的 Station 接口**

把现有 `export interface Station { ... }`（约 models.ts:244-252）整体替换为：

```ts
/** 电站资产（iot_station；stationId 为 Long，序列化为字符串，同雪花约定） */
export interface Station {
  stationId: string
  tenantId?: string
  enterpriseId?: string | null
  stationCode?: string | null
  stationName: string
  address?: string | null
  longitude?: number | null
  latitude?: number | null
  installCapacity?: number | null
  pcsCapacity?: number | null
  batteryCapacity?: number | null
  gridType?: string | null
  status?: number
  createTime?: string
  updateTime?: string
}
```

- [ ] **Step 2: 在 models.ts 新增两个 SaveReq 接口**

在 Station 接口定义之后插入：

```ts
/** 单位创建/更新请求（后端 SysEnterpriseSaveReq；parentId 空=顶级） */
export interface SysEnterpriseSaveReq {
  enterpriseCode: string
  enterpriseName: string
  parentId?: string
  sort?: number
  status?: number
}

/** 电站创建/更新请求（后端 StationSaveReq；capacity 均为可空数值） */
export interface StationSaveReq {
  stationCode: string
  stationName: string
  enterpriseId: string
  address?: string
  longitude?: number | null
  latitude?: number | null
  installCapacity?: number | null
  pcsCapacity?: number | null
  batteryCapacity?: number | null
  gridType?: string
  status?: number
}
```

- [ ] **Step 3: 补全 enterpriseApi（system.ts）**

在 `system.ts` 顶部类型导入中加入 `SysEnterpriseSaveReq`（与 `SysEnterprise` 一行已有导出），并把 `export const enterpriseApi = { ... }` 整体替换为：

```ts
export const enterpriseApi = {
  /** 组织树（单位管理页左树） */
  tree(): Promise<SysEnterprise[]> {
    return http.get('/api/system/enterprise/tree')
  },
  /** 全量企业列表（下拉用） */
  list(): Promise<SysEnterprise[]> {
    return http.get('/api/system/enterprise/list')
  },
  detail(enterpriseId: string): Promise<SysEnterprise> {
    return http.get(`/api/system/enterprise/${enterpriseId}`)
  },
  create(body: SysEnterpriseSaveReq): Promise<string> {
    return http.post('/api/system/enterprise', body)
  },
  update(enterpriseId: string, body: SysEnterpriseSaveReq): Promise<void> {
    return http.put(`/api/system/enterprise/${enterpriseId}`, body)
  },
  remove(enterpriseId: string): Promise<void> {
    return http.delete(`/api/system/enterprise/${enterpriseId}`)
  },
  switchStatus(enterpriseId: string, status: number): Promise<void> {
    return http.put(`/api/system/enterprise/${enterpriseId}/status?status=${status}`)
  },
}
```

- [ ] **Step 4: 补全 stationApi（station.ts）**

在 `station.ts` 顶部类型导入中加入 `StationSaveReq`（与 `Station` 同行已有 `PageResult, Station` 导入），并把 `export const stationApi = { ... }` 整体替换为：

```ts
/** 电站资产 API（网关路由 /api/station/** → energy-station，StripPrefix=1） */
export const stationApi = {
  /** GET /api/station/page 分页查询（后端 StationQuery 用 pageNum，勿与 EMS 的 pageNo 混淆） */
  stationPage(params: Record<string, unknown>): Promise<PageResult<Station>> {
    return http.get('/api/station/page', { params })
  },
  create(body: StationSaveReq): Promise<string> {
    return http.post('/api/station', body)
  },
  detail(stationId: string): Promise<Station> {
    return http.get(`/api/station/${stationId}`)
  },
  update(stationId: string, body: StationSaveReq): Promise<void> {
    return http.put(`/api/station/${stationId}`, body)
  },
  remove(stationId: string): Promise<void> {
    return http.delete(`/api/station/${stationId}`)
  },
}
```

- [ ] **Step 5: 类型检查与回归**

Run: `npx vue-tsc --noEmit` → 期望 EXIT 0；Run: `npm test` → 期望 86/86 全绿（薄层封装不设单测，代码库约定）。

- [ ] **Step 6: Commit**

```bash
git add :/frontend/src/types/models.ts :/frontend/src/api/system.ts :/frontend/src/api/station.ts
git commit -m "feat(api): 补全 enterprise/station API 封装与类型"
```

---

### Task 2: 共享树工具 + 字典（TDD）

**Files:**
- Create: `frontend/src/utils/enterpriseTree.ts`
- Create: `frontend/src/utils/__tests__/enterpriseTree.spec.ts`
- Modify: `frontend/src/utils/dicts.ts`（追加 GRID_TYPE_OPTIONS / stationStatusText / stationStatusTag）

**Interfaces:**
- Consumes: `SysEnterprise` 类型（Task 1）。
- Produces: `findEnterpriseNode(tree, id): SysEnterprise | null`、`flatEnterpriseTree(tree): SysEnterprise[]`、`collectSubtreeIds(node): Set<string>`、`GRID_TYPE_OPTIONS`、`stationStatusText(s): string`、`stationStatusTag(s): 'success' | 'info'`。Task 3/4 依赖。

- [ ] **Step 1: 写失败测试**

创建 `frontend/src/utils/__tests__/enterpriseTree.spec.ts`：

```ts
import { describe, expect, it } from 'vitest'
import { collectSubtreeIds, findEnterpriseNode, flatEnterpriseTree } from '@/utils/enterpriseTree'
import type { SysEnterprise } from '@/types/models'

function node(id: string, name: string, children?: SysEnterprise[]): SysEnterprise {
  return {
    enterpriseId: id, tenantId: '1', parentId: '0', path: `/${id}/`, level: 1,
    enterpriseCode: name, enterpriseName: name, sort: 0, status: 1,
    createTime: '', updateTime: '', deleted: 0, children,
  }
}

const tree: SysEnterprise[] = [
  node('1', '集团总部', [
    node('2', '华东区域', [node('3', '上海公司'), node('4', '浙江公司')]),
    node('5', '华南区域', [node('6', '广东公司')]),
  ]),
]

describe('findEnterpriseNode', () => {
  it('命中根节点', () => {
    expect(findEnterpriseNode(tree, '1')?.enterpriseName).toBe('集团总部')
  })
  it('命中深层子节点', () => {
    expect(findEnterpriseNode(tree, '6')?.enterpriseName).toBe('广东公司')
  })
  it('未命中返回 null', () => {
    expect(findEnterpriseNode(tree, '99')).toBeNull()
  })
  it('空树返回 null', () => {
    expect(findEnterpriseNode([], '1')).toBeNull()
  })
  it('null/undefined id 返回 null', () => {
    expect(findEnterpriseNode(tree, null)).toBeNull()
    expect(findEnterpriseNode(tree, undefined)).toBeNull()
  })
})

describe('flatEnterpriseTree', () => {
  it('先序平铺全部节点', () => {
    expect(flatEnterpriseTree(tree).map((n) => n.enterpriseId)).toEqual(['1', '2', '3', '4', '5', '6'])
  })
  it('空树返回空数组', () => {
    expect(flatEnterpriseTree([])).toEqual([])
  })
})

describe('collectSubtreeIds', () => {
  it('收集自身与全部后代', () => {
    const root = findEnterpriseNode(tree, '1')!
    expect(Array.from(collectSubtreeIds(root))).toEqual(['1', '2', '3', '4', '5', '6'])
  })
  it('叶子节点仅自身', () => {
    const leaf = findEnterpriseNode(tree, '3')!
    expect(Array.from(collectSubtreeIds(leaf))).toEqual(['3'])
  })
})
```

- [ ] **Step 2: 运行确认失败**

Run: `npx vitest run src/utils/__tests__/enterpriseTree.spec.ts`
期望：FAIL —— 找不到 `@/utils/enterpriseTree` 模块（该文件尚不存在）。

- [ ] **Step 3: 实现 enterpriseTree.ts**

创建 `frontend/src/utils/enterpriseTree.ts`：

```ts
import type { SysEnterprise } from '@/types/models'

/** 在单位树中按 enterpriseId 深搜（先序）。id 为空或未命中返回 null。 */
export function findEnterpriseNode(
  tree: SysEnterprise[],
  id: string | null | undefined,
): SysEnterprise | null {
  if (!id) return null
  const key = String(id)
  for (const node of tree) {
    if (String(node.enterpriseId) === key) return node
    if (node.children?.length) {
      const hit = findEnterpriseNode(node.children, id)
      if (hit) return hit
    }
  }
  return null
}

/** 先序平铺整棵树（上级下拉 / 遍历用）。 */
export function flatEnterpriseTree(tree: SysEnterprise[]): SysEnterprise[] {
  const out: SysEnterprise[] = []
  const walk = (nodes: SysEnterprise[]): void => {
    for (const n of nodes) {
      out.push(n)
      if (n.children?.length) walk(n.children)
    }
  }
  walk(tree)
  return out
}

/** 收集节点及其全部后代的 enterpriseId 集合（编辑上级时排除自身子树）。 */
export function collectSubtreeIds(node: SysEnterprise): Set<string> {
  const ids = new Set<string>([String(node.enterpriseId)])
  const walk = (n: SysEnterprise): void => {
    for (const c of n.children ?? []) {
      ids.add(String(c.enterpriseId))
      walk(c)
    }
  }
  walk(node)
  return ids
}
```

- [ ] **Step 4: 追加字典导出（dicts.ts 文件末尾）**

```ts
/** 电站电网类型下拉（后端 Station.gridType 备注：工商业/园区/电网侧） */
export const GRID_TYPE_OPTIONS = ['工商业', '园区', '电网侧']

/** 电站状态：0 停运 1 运行 */
export function stationStatusText(s: number): string {
  return s === 1 ? '运行' : s === 0 ? '停运' : `未知(${s})`
}
export function stationStatusTag(s: number): 'success' | 'info' {
  return s === 1 ? 'success' : 'info'
}
```

- [ ] **Step 5: 运行确认通过**

Run: `npx vitest run src/utils/__tests__/enterpriseTree.spec.ts` → 期望 9/9 PASS。
Run: `npx vue-tsc --noEmit` → EXIT 0；Run: `npm test` → 全绿。

- [ ] **Step 6: Commit**

```bash
git add :/frontend/src/utils/enterpriseTree.ts :/frontend/src/utils/__tests__/enterpriseTree.spec.ts :/frontend/src/utils/dicts.ts
git commit -m "feat(utils): 单位树工具(find/flat/subtree) + 电站字典"
```

---

### Task 3: 单位管理页（左树右详情）

**Files:**
- Create: `frontend/src/views/Enterprise.vue`

**Interfaces:**
- Consumes: `enterpriseApi.*`（Task 1）、`findEnterpriseNode`/`flatEnterpriseTree`/`collectSubtreeIds`（Task 2）、`productStatusText`（现有 dicts）、`hasPermi`（现有）、`toLocal`（现有 `@/utils/alarmFormat`）。
- Produces: `/archive/enterprise` 视图（Task 5 挂路由）。

- [ ] **Step 1: 创建 Enterprise.vue**

创建 `frontend/src/views/Enterprise.vue`，内容完整如下（script + template + style）：

```vue
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
```

- [ ] **Step 2: 类型检查与回归**

Run: `npx vue-tsc --noEmit` → 期望 EXIT 0；Run: `npm test` → 期望全绿。

- [ ] **Step 3: Commit**

```bash
git add :/frontend/src/views/Enterprise.vue
git commit -m "feat(web): 单位管理页（左树右详情）"
```

---

### Task 4: 电站管理页（标准表格 CRUD）

**Files:**
- Create: `frontend/src/views/Station.vue`

**Interfaces:**
- Consumes: `stationApi.*`（Task 1）、`enterpriseApi.list`（Task 1）、`GRID_TYPE_OPTIONS`/`stationStatusText`/`stationStatusTag`（Task 2）。
- Produces: `/archive/station` 视图（Task 5 挂路由）。

- [ ] **Step 1: 创建 Station.vue**

创建 `frontend/src/views/Station.vue`，内容完整如下：

```vue
<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { stationApi } from '@/api/station'
import { enterpriseApi } from '@/api/system'
import type { Station, StationSaveReq, SysEnterprise } from '@/types/models'
import { GRID_TYPE_OPTIONS, stationStatusTag, stationStatusText } from '@/utils/dicts'

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
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : String(e)) }
  finally { loading.value = false }
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
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
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
```

- [ ] **Step 2: 类型检查与回归**

Run: `npx vue-tsc --noEmit` → 期望 EXIT 0；Run: `npm test` → 期望全绿。

- [ ] **Step 3: Commit**

```bash
git add :/frontend/src/views/Station.vue
git commit -m "feat(web): 电站管理页（标准表格 CRUD）"
```

---

### Task 5: 路由 + 导航组 + 全量回归

**Files:**
- Modify: `frontend/src/router/index.ts`（挂 2 条子路由）
- Modify: `frontend/src/layouts/MainLayout.vue`（基础档案组）

**Interfaces:**
- Consumes: Task 3/4 创建的 `@/views/Enterprise.vue`、`@/views/Station.vue`。

- [ ] **Step 1: 挂路由**

在 `frontend/src/router/index.ts` 的 `ems/plan` 路由项（`{ path: 'ems/plan', ... meta: { title: '充放电计划', icon: 'TrendCharts' } }`）之后插入：

```ts
      {
        path: 'archive/enterprise',
        name: 'Enterprise',
        component: () => import('@/views/Enterprise.vue'),
        meta: { title: '单位管理', icon: 'OfficeBuilding' },
      },
      {
        path: 'archive/station',
        name: 'Station',
        component: () => import('@/views/Station.vue'),
        meta: { title: '电站管理', icon: 'Monitor' },
      },
```

- [ ] **Step 2: 加导航组**

在 `frontend/src/layouts/MainLayout.vue` 的 `menus` 数组里，`{ path: '/ems/plan', title: '充放电计划' }` 之后、`{ group: '/system', ... }` 之前插入：

```ts
  {
    group: '/archive',
    title: '基础档案',
    children: [
      { path: '/archive/enterprise', title: '单位管理', perms: ['system:enterprise:list'] },
      { path: '/archive/station', title: '电站管理' },
    ],
  },
```

- [ ] **Step 3: 全量回归**

Run: `npx vue-tsc --noEmit` → 期望 EXIT 0；Run: `npm test` → 期望全绿（含新增 enterpriseTree 用例）。
Run: `git status` → 期望仅有 2 个本机本地文件 M（BrokerProperties.java / vite.config.ts），无其他未提交改动。

- [ ] **Step 4: Commit**

```bash
git add :/frontend/src/router/index.ts :/frontend/src/layouts/MainLayout.vue
git commit -m "feat(web): 基础档案导航组 + 路由"
```

---

### Task 6: 浏览器冒烟

**Files:**
- Create（临时目录，不入库）: `C:/Users/linwe/AppData/Local/Temp/ems-s1-smoke/smoke-archive.mjs`
- Create: `C:/Users/linwe/AppData/Local/Temp/ems-s1-smoke/smoke-archive-report.md`

**Interfaces:**
- Consumes: 全部任务成果 + 本地运行中的网关（http://127.0.0.1:8000）与 vite dev（http://127.0.0.1:25173）。

- [ ] **Step 1: 写冒烟脚本**

在临时目录写 `smoke-archive.mjs`，用 playwright-core + Edge 无头（`C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe`），BASE `http://127.0.0.1:25173`，登录 admin/admin123（按钮文案「登 录」，登录后等 URL 变化）。断言如下（每步 console 输出 PASS/FAIL，脚本非 0 退出码标记失败）：

1. 登录后侧栏可见「基础档案」组，点击「单位管理」进入 `/archive/enterprise`。
2. 新增根单位：点「新增根单位」，填编码 `SMOKEENT<时间戳末6位>`、名称「冒烟根单位」，保存 → 树中出现该节点；点击节点 → 右侧详情显示名称、上级=顶级、状态=启用。
3. 新增子单位：选根节点 → 点「新增子单位」→ 弹窗「上级单位」为禁用只读输入且显示根名 → 填编码 `SMOKEENTC<时间戳末6位>`、名称「冒烟子单位」→ 保存 → 树中出现子节点。
4. 编辑：选子节点 → 点「编辑」→ 上级单位下拉可清空/重选 → 改名称「冒烟子单位改」→ 保存 → 详情名称已更新。
5. 删除根被拦截：选根节点 → 点「删除」→ 出现 `.el-message--warning` 且文本含「存在子单位」→ 根节点仍在树中。
6. 删除子 → 再删根：选子节点 → 删除 → confirm → 子节点消失；选根节点 → 删除 → confirm → 根节点消失。
7. 停用/启用：新增一个根单位 → 选它 → 点「停用」→ 详情状态 tag 变「禁用」；点「启用」→ 恢复「启用」；最后删除该根（无子可删）。
8. 电站管理：点击「电站管理」进入 `/archive/station` → 「新增电站」→ 填编码 `SMOKEST<时间戳末6位>`、名称「冒烟电站」、所属单位下拉选择「冒烟根单位」（若第 7 步已删则新建一个根单位供选择）→ 保存 → 表格行出现且所属单位列显示单位名。
9. 下拉联动：电站列表确认存在 → 导航到 `/ems/plan` → 点「生成计划」打开弹窗 → 电站下拉中能选中「冒烟电站」（断言选项文本）。
10. 编辑电站：回 `/archive/station` → 编辑该电站改名称 → 保存 → 表格更新。
11. 删除电站：删除该电站 → confirm → 行消失。
12. 清理：删除本脚本创建的根单位（先删其下电站/子单位）；如已全删则跳过。报告最终 PASS/FAIL 统计。

**唯一性铁律：** 每次运行生成新 code（含 Date.now() 末 6 位），绝不复用历史 code —— 电站/单位编码唯一索引含软删行，复用会撞 CONFLICT（P2 已踩坑）。冒烟产生的数据全部清理。

- [ ] **Step 2: 运行并核对**

Run: `node C:/Users/linwe/AppData/Local/Temp/ems-s1-smoke/smoke-archive.mjs`
期望：全部场景 PASS（若有 FAIL 逐条定位是脚本断言问题还是产品缺陷，脚本断言问题修正后重跑；产品缺陷如实记录）。

- [ ] **Step 3: 写报告**

写 `smoke-archive-report.md`：逐场景 PASS/FAIL + 结论（ALL_PASS 或缺陷清单）。不入库，仅作记录。
