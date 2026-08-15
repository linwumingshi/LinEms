<script setup lang="ts">
/**
 * 通知模板管理（energy-notify）。
 * 模板按消息类型组织、绑定渠道，content/title 支持 ${xxx} 占位符（发送时由上下文渲染）。
 */
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { notifyApi } from '@/api/notify'
import type { NotifyChannelOption, NotifyTemplate } from '@/types/models'

const list = ref<NotifyTemplate[]>([])
const loading = ref(false)
const channels = ref<NotifyChannelOption[]>([])
const filterChannel = ref('')

const dialog = ref(false)
const saving = ref(false)
const editId = ref<string | null>(null)

const form = reactive({
  templateCode: '',
  templateName: '',
  messageType: 'SCENE',
  channel: 'WEBHOOK',
  titleTemplate: '',
  contentTemplate: '',
  description: '',
  status: 1,
})

const messageTypeOptions = [
  { value: 'ALARM', label: '告警' },
  { value: 'SCENE', label: '场景联动' },
  { value: 'DEVICE_EVENT', label: '设备事件' },
  { value: 'SYSTEM', label: '系统' },
]

const channelLabels: Record<string, string> = {
  WEBHOOK: 'Webhook',
  WECOM: '企业微信',
  DINGTALK: '钉钉',
  EMAIL: '邮件',
}

/** 常见占位符提示（复制用） */
const placeholderTips = [
  { key: '${deviceName}', desc: '设备名称' },
  { key: '${deviceId}', desc: '设备ID' },
  { key: '${productKey}', desc: '产品标识' },
  { key: '${ruleName}', desc: '规则/告警名称' },
  { key: '${ruleCode}', desc: '规则/告警编码' },
  { key: '${value}', desc: '触发值' },
  { key: '${severity}', desc: '级别' },
  { key: '${ts}', desc: '触发时间' },
]

async function load(): Promise<void> {
  loading.value = true
  try {
    list.value = await notifyApi.templates(filterChannel.value || undefined)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    loading.value = false
  }
}

async function loadChannels(): Promise<void> {
  try {
    channels.value = (await notifyApi.channels()).filter((c) => c.supported === 'true')
  } catch {
    channels.value = []
  }
}

function resetForm(): void {
  form.templateCode = ''
  form.templateName = ''
  form.messageType = 'SCENE'
  form.channel = 'WEBHOOK'
  form.titleTemplate = ''
  form.contentTemplate = ''
  form.description = ''
  form.status = 1
}

function openCreate(): void {
  editId.value = null
  resetForm()
  dialog.value = true
}

function openEdit(row: NotifyTemplate): void {
  editId.value = row.templateId
  resetForm()
  form.templateCode = row.templateCode
  form.templateName = row.templateName
  form.messageType = row.messageType
  form.channel = row.channel
  form.titleTemplate = row.titleTemplate ?? ''
  form.contentTemplate = row.contentTemplate
  form.description = row.description ?? ''
  form.status = row.status
  dialog.value = true
}

/** 插入占位符到光标所在输入框（简单实现：追加到内容末尾） */
function appendPlaceholder(key: string, target: 'title' | 'content'): void {
  if (target === 'title') form.titleTemplate += key
  else form.contentTemplate += key
}

async function save(): Promise<void> {
  if (!form.templateCode.trim()) { ElMessage.warning('请填写模板编码'); return }
  if (!form.templateName.trim()) { ElMessage.warning('请填写模板名称'); return }
  if (!form.contentTemplate.trim()) { ElMessage.warning('请填写内容模板'); return }
  const body = {
    templateCode: form.templateCode.trim(),
    templateName: form.templateName.trim(),
    messageType: form.messageType,
    channel: form.channel,
    titleTemplate: form.titleTemplate.trim() || null,
    contentTemplate: form.contentTemplate,
    status: form.status,
    description: form.description.trim() || null,
  }
  saving.value = true
  try {
    if (editId.value) {
      await notifyApi.updateTemplate(editId.value, body)
      ElMessage.success('模板已更新')
    } else {
      await notifyApi.createTemplate(body)
      ElMessage.success('模板已创建')
    }
    dialog.value = false
    void load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    saving.value = false
  }
}

async function remove(row: NotifyTemplate): Promise<void> {
  try {
    await ElMessageBox.confirm(`确定删除通知模板「${row.templateName}」？`, '删除确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await notifyApi.deleteTemplate(row.templateId)
    ElMessage.success('模板已删除')
    void load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  }
}

onMounted(() => {
  void load()
  void loadChannels()
})
</script>

<template>
  <div class="ex-page">
    <header class="ex-page-head">
      <div class="head-title">
        <h1 class="ex-title">通知模板</h1>
        <p class="ex-sub">按消息类型组织通知内容 · 支持 ${占位符} 由发送上下文渲染</p>
      </div>
      <el-button type="primary" @click="openCreate">新增模板</el-button>
    </header>

    <section class="ex-card filter-card">
      <el-form inline @submit.prevent>
        <el-form-item label="渠道">
          <el-select v-model="filterChannel" clearable placeholder="全部" style="width: 160px" @change="load">
            <el-option v-for="c in channels" :key="c.code" :label="c.label" :value="c.code" />
          </el-select>
        </el-form-item>
      </el-form>
    </section>

    <section class="ex-card">
      <el-table :data="list" v-loading="loading" size="default">
        <el-table-column prop="templateCode" label="模板编码" width="160" />
        <el-table-column prop="templateName" label="名称" min-width="140" show-overflow-tooltip />
        <el-table-column label="消息类型" width="110">
          <template #default="{ row }">
            <el-tag size="small">{{ messageTypeOptions.find((m) => m.value === row.messageType)?.label ?? row.messageType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="渠道" width="110">
          <template #default="{ row }">
            <el-tag size="small" type="info">{{ channelLabels[row.channel] ?? row.channel }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="contentTemplate" label="内容模板" min-width="200" show-overflow-tooltip />
        <el-table-column label="状态" width="80" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="130" align="center">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" size="small" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无通知模板，点击右上角「新增模板」创建" />
        </template>
      </el-table>
    </section>

    <!-- 模板表单 -->
    <el-dialog v-model="dialog" :title="editId ? `编辑通知模板 · ${form.templateName}` : '新增通知模板'" width="640px">
      <el-form label-width="110px">
        <el-form-item label="模板编码" required>
          <el-input v-model="form.templateCode" placeholder="如 TPL_SCENE_ALARM（租户内唯一）" :disabled="!!editId" style="width: 280px" />
        </el-form-item>
        <el-form-item label="模板名称" required>
          <el-input v-model="form.templateName" placeholder="如 场景告警通知" style="width: 280px" />
        </el-form-item>
        <el-form-item label="消息类型" required>
          <el-select v-model="form.messageType" style="width: 200px">
            <el-option v-for="m in messageTypeOptions" :key="m.value" :label="m.label" :value="m.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="渠道" required>
          <el-select v-model="form.channel" style="width: 200px">
            <el-option v-for="c in channels" :key="c.code" :label="`${c.label}（${c.code}）`" :value="c.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="标题模板">
          <el-input v-model="form.titleTemplate" placeholder="如 【告警】${ruleName} 触发（邮件主题/企微标题）" />
        </el-form-item>
        <el-form-item label="内容模板" required>
          <el-input v-model="form.contentTemplate" type="textarea" :rows="4" placeholder='如 设备 ${deviceName} 触发 ${ruleName}，当前值 ${value}，时间 ${ts}' />
        </el-form-item>
        <el-form-item label="占位符">
          <div class="ph-wrap">
            <el-tag
              v-for="p in placeholderTips"
              :key="p.key"
              size="small"
              type="info"
              class="ph-tag"
              :title="p.desc"
              @click="appendPlaceholder(p.key, 'content')"
            >
              {{ p.key }}
            </el-tag>
            <span class="ph-hint">点击追加到内容末尾；也可在标题框手输</span>
          </div>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.ph-wrap {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
  width: 100%;
}
.ph-tag {
  cursor: pointer;
  font-family: ui-monospace, 'Consolas', monospace;
}
.ph-hint {
  font-size: 12px;
  color: var(--ex-ink-3);
}
</style>
