<script setup lang="ts">
/**
 * 通知配置管理（energy-notify）。
 * 渠道：WEBHOOK/WECOM/DINGTALK/EMAIL；channel_config 按渠道动态表单，JSON 存储。
 */
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { notifyApi } from '@/api/notify'
import type { NotifyChannelOption, NotifyConfig } from '@/types/models'

const list = ref<NotifyConfig[]>([])
const loading = ref(false)
const channels = ref<NotifyChannelOption[]>([])

const dialog = ref(false)
const saving = ref(false)
const editId = ref<string | null>(null)

/** 表单（channelConfig 分渠道字段，保存时组装 JSON） */
const form = reactive({
  configCode: '',
  configName: '',
  channel: 'WEBHOOK',
  status: 1,
  description: '',
  // WEBHOOK
  url: '',
  headers: '',
  // WECOM / DINGTALK
  webhook: '',
  secret: '',
  // EMAIL
  host: '',
  port: 465,
  username: '',
  password: '',
  from: '',
  to: '',
  ssl: true,
})

const channelLabels: Record<string, string> = {
  WEBHOOK: 'Webhook',
  WECOM: '企业微信',
  DINGTALK: '钉钉',
  EMAIL: '邮件',
}

async function load(): Promise<void> {
  loading.value = true
  try {
    list.value = await notifyApi.configs()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    loading.value = false
  }
}

async function loadChannels(): Promise<void> {
  try {
    channels.value = await notifyApi.channels()
  } catch {
    channels.value = []
  }
}

function resetForm(): void {
  form.configCode = ''
  form.configName = ''
  form.channel = 'WEBHOOK'
  form.status = 1
  form.description = ''
  form.url = ''
  form.headers = ''
  form.webhook = ''
  form.secret = ''
  form.host = ''
  form.port = 465
  form.username = ''
  form.password = ''
  form.from = ''
  form.to = ''
  form.ssl = true
}

function openCreate(): void {
  editId.value = null
  resetForm()
  dialog.value = true
}

function openEdit(row: NotifyConfig): void {
  editId.value = row.configId
  resetForm()
  form.configCode = row.configCode
  form.configName = row.configName
  form.channel = row.channel
  form.status = row.status
  form.description = row.description ?? ''
  try {
    const cfg = JSON.parse(row.channelConfig) as Record<string, never>
    form.url = String(cfg.url ?? '')
    form.headers = cfg.headers ? JSON.stringify(cfg.headers, null, 2) : ''
    form.webhook = String(cfg.webhook ?? '')
    form.secret = String(cfg.secret ?? '')
    form.host = String(cfg.host ?? '')
    form.port = Number(cfg.port ?? 465)
    form.username = String(cfg.username ?? '')
    form.password = String(cfg.password ?? '')
    form.from = String(cfg.from ?? '')
    form.to = String(cfg.to ?? '')
    form.ssl = cfg.ssl !== false
  } catch {
    // channelConfig 解析失败保留空表单
  }
  dialog.value = true
}

/** 按当前渠道组装 channel_config JSON（简单结构校验） */
function buildChannelConfig(): string {
  switch (form.channel) {
    case 'WEBHOOK': {
      if (!form.url.trim()) throw new Error('Webhook 地址必填')
      const cfg: Record<string, unknown> = { url: form.url.trim() }
      if (form.headers.trim()) {
        try {
          cfg.headers = JSON.parse(form.headers)
        } catch {
          throw new Error('headers 需为合法 JSON')
        }
      }
      return JSON.stringify(cfg)
    }
    case 'WECOM': {
      if (!form.webhook.trim()) throw new Error('企业微信 webhook 必填')
      return JSON.stringify({ webhook: form.webhook.trim() })
    }
    case 'DINGTALK': {
      if (!form.webhook.trim()) throw new Error('钉钉 webhook 必填')
      const cfg: Record<string, unknown> = { webhook: form.webhook.trim() }
      if (form.secret.trim()) cfg.secret = form.secret.trim()
      return JSON.stringify(cfg)
    }
    case 'EMAIL': {
      if (!form.host.trim() || !form.username.trim() || !form.from.trim()) {
        throw new Error('邮件需填写 host/username/from')
      }
      return JSON.stringify({
        host: form.host.trim(),
        port: form.port,
        username: form.username.trim(),
        password: form.password.trim(),
        from: form.from.trim(),
        to: form.to.trim() || undefined,
        ssl: form.ssl,
      })
    }
    default:
      throw new Error(`暂不支持渠道 ${form.channel}`)
  }
}

async function save(): Promise<void> {
  if (!form.configCode.trim()) { ElMessage.warning('请填写配置编码'); return }
  if (!form.configName.trim()) { ElMessage.warning('请填写配置名称'); return }
  let channelConfig: string
  try {
    channelConfig = buildChannelConfig()
  } catch (e) {
    ElMessage.warning(e instanceof Error ? e.message : String(e))
    return
  }
  const body = {
    configCode: form.configCode.trim(),
    configName: form.configName.trim(),
    channel: form.channel,
    channelConfig,
    status: form.status,
    description: form.description.trim() || null,
  }
  saving.value = true
  try {
    if (editId.value) {
      await notifyApi.updateConfig(editId.value, body)
      ElMessage.success('配置已更新')
    } else {
      await notifyApi.createConfig(body)
      ElMessage.success('配置已创建')
    }
    dialog.value = false
    void load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    saving.value = false
  }
}

async function remove(row: NotifyConfig): Promise<void> {
  try {
    await ElMessageBox.confirm(`确定删除通知配置「${row.configName}」？`, '删除确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await notifyApi.deleteConfig(row.configId)
    ElMessage.success('配置已删除')
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
        <h1 class="ex-title">通知配置</h1>
        <p class="ex-sub">渠道接入（Webhook / 企业微信 / 钉钉 / 邮件）· 场景联动动作与模板复用的发送通道</p>
      </div>
      <el-button type="primary" @click="openCreate">新增配置</el-button>
    </header>

    <section class="ex-card">
      <el-table :data="list" v-loading="loading" size="default">
        <el-table-column prop="configCode" label="配置编码" width="160" />
        <el-table-column prop="configName" label="名称" min-width="140" show-overflow-tooltip />
        <el-table-column label="渠道" width="120">
          <template #default="{ row }">
            <el-tag size="small">{{ channelLabels[row.channel] ?? row.channel }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="描述" min-width="140" show-overflow-tooltip />
        <el-table-column label="操作" width="130" align="center">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" size="small" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无通知配置，点击右上角「新增配置」创建" />
        </template>
      </el-table>
    </section>

    <!-- 配置表单 -->
    <el-dialog v-model="dialog" :title="editId ? `编辑通知配置 · ${form.configName}` : '新增通知配置'" width="600px">
      <el-form label-width="110px">
        <el-form-item label="配置编码" required>
          <el-input v-model="form.configCode" placeholder="如 WEBHOOK_OPS（租户内唯一）" :disabled="!!editId" style="width: 260px" />
        </el-form-item>
        <el-form-item label="配置名称" required>
          <el-input v-model="form.configName" placeholder="如 运维 Webhook" style="width: 260px" />
        </el-form-item>
        <el-form-item label="渠道" required>
          <el-select v-model="form.channel" style="width: 220px" :disabled="!!editId">
            <el-option v-for="c in channels" :key="c.code" :label="`${c.label}（${c.code}）${c.supported === 'true' ? '' : ' · 未实现'}`" :value="c.code" />
          </el-select>
        </el-form-item>

        <!-- WEBHOOK -->
        <template v-if="form.channel === 'WEBHOOK'">
          <el-form-item label="URL" required>
            <el-input v-model="form.url" placeholder="https://example.com/hook" />
          </el-form-item>
          <el-form-item label="Headers">
            <el-input v-model="form.headers" type="textarea" :rows="3" placeholder='可选，JSON 对象，如 {"X-Auth":"token123"}' spellcheck="false" />
          </el-form-item>
        </template>

        <!-- WECOM / DINGTALK -->
        <template v-if="form.channel === 'WECOM' || form.channel === 'DINGTALK'">
          <el-form-item :label="form.channel === 'WECOM' ? 'Webhook' : 'Webhook'" required>
            <el-input v-model="form.webhook" :placeholder="form.channel === 'WECOM' ? 'https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=...' : 'https://oapi.dingtalk.com/robot/send?access_token=...'" />
          </el-form-item>
          <el-form-item v-if="form.channel === 'DINGTALK'" label="加签密钥">
            <el-input v-model="form.secret" placeholder="机器人加签 secret（SEC...），留空则不加签" />
          </el-form-item>
        </template>

        <!-- EMAIL -->
        <template v-if="form.channel === 'EMAIL'">
          <el-form-item label="SMTP 主机" required>
            <el-input v-model="form.host" placeholder="如 smtp.qq.com" style="width: 260px" />
          </el-form-item>
          <el-form-item label="端口">
            <el-input-number v-model="form.port" :min="1" :max="65535" />
          </el-form-item>
          <el-form-item label="账号" required>
            <el-input v-model="form.username" placeholder="邮箱账号" style="width: 260px" />
          </el-form-item>
          <el-form-item label="授权码">
            <el-input v-model="form.password" type="password" show-password placeholder="SMTP 授权码/密码" style="width: 260px" />
          </el-form-item>
          <el-form-item label="发件人" required>
            <el-input v-model="form.from" placeholder="xx@qq.com" style="width: 260px" />
          </el-form-item>
          <el-form-item label="固定收件人">
            <el-input v-model="form.to" placeholder="可空；多个用逗号分隔，发送时可用 context.to 覆盖" style="width: 300px" />
          </el-form-item>
          <el-form-item label="SSL">
            <el-switch v-model="form.ssl" />
            <span class="field-hint">开启走 SSL（默认 465），关闭走 STARTTLS（587）</span>
          </el-form-item>
        </template>

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
.field-hint {
  font-size: 12px;
  color: var(--ex-ink-3);
  margin-left: 6px;
}
</style>
