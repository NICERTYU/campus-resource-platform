<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import PageHeading from '../components/PageHeading.vue'
import PagePager from '../components/PagePager.vue'
import StatusTag from '../components/StatusTag.vue'
import { usePagination } from '../lib/pagination'
import { useAuth } from '../stores/auth'
import { request, write, errorMessage, ApiError } from '../lib/api'
import {
  approvalDate,
  approvalStage,
  approvalNotice,
  approvalFailure,
  approvalResultMessage,
  canPerformApprovalAction,
  canViewApprovalHistory,
  isApprovalPolicy,
  type ApprovalPolicy,
  type ApprovalAction,
  type ApprovalFeedback,
} from '../lib/applications'
import { interval, nextSlot, isoMinute, validTimes } from '../lib/time'
import type { Application, Classroom, ApprovalHistory } from '../lib/types'
const auth = useAuth()
const records = ref<Application[]>([]),
  rooms = ref<Classroom[]>([])
const loading = ref(false),
  saving = ref(false),
  error = ref(''),
  policyError = ref('')
const policy = ref<ApprovalPolicy | null>(null),
  now = ref(Date.now())
const feedback = ref<ApprovalFeedback | null>(null),
  resultMessage = ref('')
const createOpen = ref(false),
  roomId = ref<number>(),
  date = ref(nextSlot().date)
const start = ref(`${String(nextSlot().hour).padStart(2, '0')}:00`)
const end = ref(`${String(nextSlot().hour + 1).padStart(2, '0')}:00`),
  purpose = ref('')
const action = ref<{ record: Application; type: ApprovalAction } | null>(null),
  comment = ref('')
const actionRejected = ref(false)
const historyOpen = ref(false),
  historyRecord = ref<Application | null>(null)
const history = ref<ApprovalHistory[]>([]),
  historyLoading = ref(false),
  historyError = ref('')
const traces = ref<Record<string, string>>({})
const tracePending = new Set<string>()
const pager = usePagination()
let active = true,
  listPending = false,
  loadGeneration = 0,
  policyGeneration = 0,
  historyGeneration = 0,
  accessEpoch = 0
let poll: ReturnType<typeof setInterval> | undefined,
  clock: ReturnType<typeof setInterval> | undefined
const actionTitle: Record<ApprovalAction, string> = {
  approve: '通过',
  reject: '驳回',
  withdraw: '撤销',
  return: '退回',
  resubmit: '重新提交',
}
const historyAction: Record<string, string> = {
  CREATE: '提交申请',
  APPROVE: '审核通过',
  REJECT: '驳回',
  RETURN: '退回',
  RESUBMIT: '补充重提',
  WITHDRAW: '撤销申请',
  EXPIRE: '超时撤销',
}
const allowed = (record: Application, type: ApprovalAction) =>
  canPerformApprovalAction(type, record, auth.user, policy.value, now.value)
const actionInvalid = computed(() => {
  if (!action.value) return ''
  if (actionRejected.value) return '本次操作未获确认，请关闭对话框并核对最新记录后重新操作。'
  const current = records.value.find((r) => r.id === action.value!.record.id)
  if (!current || current.revision !== action.value.record.revision)
    return '记录版本已变化，请关闭对话框并核对最新记录后重新操作。'
  if (!allowed(current, action.value.type))
    return '当前节点、权限、状态或审批期限已变化，不能确认本次操作。'
  return ''
})
const traceKey = (record: Application) => `${record.id}:${record.revision}`
function clearPrivateData() {
  ++accessEpoch
  ++loadGeneration
  ++policyGeneration
  ++historyGeneration
  records.value = []
  rooms.value = []
  policy.value = null
  traces.value = {}
  tracePending.clear()
  action.value = null
  createOpen.value = false
  historyOpen.value = false
  historyRecord.value = null
  history.value = []
  loading.value = false
  listPending = false
  historyLoading.value = false
}
watch(
  () => auth.user && JSON.stringify([auth.user.id, auth.user.department, auth.user.roles]),
  () => {
    clearPrivateData()
    purpose.value = ''
    comment.value = ''
    feedback.value = null
    resultMessage.value = ''
  },
)
function sessionDenied(e: unknown) {
  if (e instanceof ApiError && e.status === 401) clearPrivateData()
}
async function loadPolicy() {
  const generation = ++policyGeneration
  try {
    const value = await request<unknown>('/classroom-applications/policy')
    if (!active || generation !== policyGeneration) return
    if (!isApprovalPolicy(value)) throw new Error('审批权限配置格式不完整，请刷新重试。')
    policy.value = value
    policyError.value = ''
  } catch (e) {
    if (!active || generation !== policyGeneration) return
    policy.value = null
    policyError.value = errorMessage(e)
    sessionDenied(e)
  }
}
async function loadRooms() {
  const epoch = accessEpoch
  try {
    const value = await request<Classroom[]>('/classrooms')
    if (!active || epoch !== accessEpoch) return
    rooms.value = value
    roomId.value = value.find((r) => r.enabled)?.id
  } catch (e) {
    if (active && epoch === accessEpoch) {
      error.value = errorMessage(e)
      sessionDenied(e)
    }
  }
}
async function loadTrace(record: Application, generation: number) {
  const key = traceKey(record)
  if (traces.value[key] || tracePending.has(key) || !canViewApprovalHistory(record, auth.user))
    return
  tracePending.add(key)
  try {
    const entries = await request<ApprovalHistory[]>(`/classroom-applications/${record.id}/history`)
    if (!active || generation !== loadGeneration) return
    const matching = entries.filter((entry) => entry.revision === record.revision).at(-1)
    if (matching?.requestId) traces.value[key] = matching.requestId
  } catch (e) {
    if (active && generation === loadGeneration) sessionDenied(e)
  } finally {
    tracePending.delete(key)
  }
}
async function load(silent = false, target = pager.page) {
  if (!Number.isInteger(target) || target < 1) return
  const generation = ++loadGeneration
  listPending = true
  if (!silent) loading.value = true
  error.value = ''
  try {
    const value = await request<Application[]>(`/classroom-applications?${pager.query(target)}`)
    if (!active || generation !== loadGeneration) return
    records.value = value
    pager.accept(target, value.length)
    for (const record of value) {
      if (record.status === 'PROCESSING' || record.status === 'EXPIRED' || record.lastError)
        void loadTrace(record, generation)
    }
  } catch (e) {
    if (!active || generation !== loadGeneration) return
    error.value = errorMessage(e)
    if (e instanceof ApiError && [401, 403].includes(e.status)) clearPrivateData()
  } finally {
    if (active && generation === loadGeneration) {
      loading.value = false
      listPending = false
    }
  }
}
async function refresh() {
  await Promise.all([load(), loadPolicy()])
}
async function showHistory(record: Application) {
  if (!canViewApprovalHistory(record, auth.user)) return
  const generation = ++historyGeneration
  historyRecord.value = record
  history.value = []
  historyError.value = ''
  historyOpen.value = true
  historyLoading.value = true
  try {
    const value = await request<ApprovalHistory[]>(`/classroom-applications/${record.id}/history`)
    if (active && generation === historyGeneration && historyOpen.value) history.value = value
  } catch (e) {
    if (active && generation === historyGeneration) {
      historyError.value = errorMessage(e)
      sessionDenied(e)
    }
  } finally {
    if (active && generation === historyGeneration) historyLoading.value = false
  }
}
function closeHistory() {
  ++historyGeneration
  historyLoading.value = false
}
async function create() {
  if (saving.value) return
  if (
    !roomId.value ||
    !date.value ||
    !validTimes(start.value, end.value) ||
    purpose.value.trim().length < 2
  ) {
    ElMessage.warning('请选择教室、有效时段并填写申请用途（至少2字）')
    return
  }
  const epoch = accessEpoch
  saving.value = true
  feedback.value = null
  resultMessage.value = ''
  try {
    await write('/classroom-applications', {
      classroomId: roomId.value,
      startTime: isoMinute(date.value, start.value),
      endTime: isoMinute(date.value, end.value),
      purpose: purpose.value.trim(),
    })
    if (!active || epoch !== accessEpoch) return
    createOpen.value = false
    purpose.value = ''
    resultMessage.value = '申请已提交，等待院系初审。'
    await load(false, 1)
  } catch (e) {
    if (active && epoch === accessEpoch) {
      feedback.value = approvalFailure(e)
      sessionDenied(e)
    }
  } finally {
    saving.value = false
  }
}
function openAction(record: Application, type: ApprovalAction) {
  now.value = Date.now()
  if (saving.value || !allowed(record, type)) return
  action.value = { record: { ...record }, type }
  feedback.value = null
  actionRejected.value = false
  comment.value = type === 'resubmit' ? record.purpose : ''
}
async function execute() {
  now.value = Date.now()
  if (saving.value || !action.value || actionInvalid.value) return
  const selected = action.value,
    epoch = accessEpoch
  if (['reject', 'return', 'resubmit'].includes(selected.type) && !comment.value.trim()) {
    ElMessage.warning('请填写原因或修改后的用途')
    return
  }
  if (selected.type === 'resubmit' && comment.value.trim().length < 2) {
    ElMessage.warning('申请用途至少填写2字')
    return
  }
  saving.value = true
  feedback.value = null
  resultMessage.value = ''
  try {
    const value = await write<Application>(
      `/classroom-applications/${selected.record.id}/${selected.type}`,
      selected.type === 'withdraw'
        ? {}
        : selected.type === 'resubmit'
          ? { purpose: comment.value.trim(), revision: selected.record.revision }
          : { comment: comment.value.trim(), revision: selected.record.revision },
    )
    if (!active || epoch !== accessEpoch) return
    action.value = null
    resultMessage.value = approvalResultMessage(selected.type, value)
    await load()
  } catch (e) {
    if (!active || epoch !== accessEpoch) return
    feedback.value = approvalFailure(e)
    actionRejected.value = true
    sessionDenied(e)
    if (!(e instanceof ApiError && e.status === 401)) await refresh()
  } finally {
    saving.value = false
  }
}
onMounted(() => {
  void Promise.all([load(), loadPolicy(), loadRooms()])
  clock = setInterval(() => {
    now.value = Date.now()
  }, 1000)
  poll = setInterval(() => {
    if (
      !loading.value &&
      !listPending &&
      !saving.value &&
      auth.user &&
      records.value.some((r) => ['PROCESSING', 'PENDING', 'RETURNED'].includes(r.status))
    )
      void load(true)
  }, 5000)
})
onUnmounted(() => {
  active = false
  clearInterval(poll)
  clearInterval(clock)
  clearPrivateData()
})
</script>
<template>
  <PageHeading
    eyebrow="ROOM APPLICATIONS"
    :title="auth.isAdmin ? '让每场活动，妥善落座。' : '为下一场活动，申请空间。'"
    description="院系初审 → 资源终审 → 确认整室占用。审批期限内可按意见补充材料。"
  >
    <el-button @click="refresh" :loading="loading">刷新</el-button>
    <el-button
      v-if="auth.has('APPLICANT') || auth.isAdmin"
      type="primary"
      @click="createOpen = true"
      >＋ 新建申请</el-button
    >
  </PageHeading>
  <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
  <el-alert
    v-if="policyError && auth.isAdmin"
    :title="`审批权限暂不可用：${policyError}`"
    description="已暂停审核按钮；可刷新重试，申请列表和记录查询仍独立加载。"
    type="warning"
    :closable="false"
  />
  <el-alert
    v-if="feedback"
    class="approval-feedback"
    :title="feedback.message"
    :description="feedback.hint"
    type="error"
    :closable="false"
  />
  <el-alert v-if="resultMessage" :title="resultMessage" type="info" :closable="false" />
  <section class="surface" v-loading="loading">
    <div class="section-kicker">
      {{ auth.isAdmin ? '申请审批台' : '我的教室申请'
      }}<small>本页 {{ records.length }} 条记录</small>
    </div>
    <div v-for="r in records" :key="r.id" class="workflow-card">
      <div class="workflow-top">
        <div>
          <span class="record-number">申请 #{{ r.id }}</span>
          <h3>{{ r.classroomName }}</h3>
        </div>
        <StatusTag :status="r.status" :label="r.status === 'PENDING' ? '待审批' : undefined" />
      </div>
      <p class="workflow-time">◷ {{ interval(r.startTime, r.endTime) }}</p>
      <p class="workflow-description">{{ r.purpose }}</p>
      <div class="approval-summary">
        <strong>{{ approvalStage(r) }}</strong
        ><span>审批节点：{{ r.currentStep ?? '—' }} / {{ r.totalSteps ?? '—' }}</span
        ><span>审批截止：{{ approvalDate(r.approvalDeadline) }}</span>
      </div>
      <p class="muted">
        申请人：{{ r.applicantName
        }}<template v-if="r.applicantDepartment"> · {{ r.applicantDepartment }}</template
        ><template v-if="r.comment"> · 审批意见：{{ r.comment }}</template>
      </p>
      <el-alert
        v-if="approvalNotice(r, now)"
        :title="approvalNotice(r, now)"
        type="info"
        :closable="false"
      />
      <el-alert v-if="r.lastError" :title="r.lastError" type="warning" :closable="false" />
      <p
        v-if="r.status === 'PROCESSING' || r.status === 'EXPIRED' || r.lastError"
        class="approval-trace muted"
      >
        请求编号：{{ traces[traceKey(r)] || '暂未取得，可打开审批记录核对或稍后刷新。' }}
      </p>
      <div class="workflow-actions">
        <template v-if="allowed(r, 'approve')"
          ><el-button type="primary" :disabled="saving" @click="openAction(r, 'approve')"
            >通过申请</el-button
          ><el-button :disabled="saving" @click="openAction(r, 'reject')">驳回</el-button
          ><el-button :disabled="saving" @click="openAction(r, 'return')">退回</el-button></template
        >
        <el-button
          v-if="allowed(r, 'withdraw')"
          :disabled="saving"
          @click="openAction(r, 'withdraw')"
          >撤销申请</el-button
        >
        <el-button
          v-if="allowed(r, 'resubmit')"
          :disabled="saving"
          @click="openAction(r, 'resubmit')"
          >补充重提</el-button
        >
        <el-button v-if="canViewApprovalHistory(r, auth.user)" @click="showHistory(r)"
          >审批记录</el-button
        >
      </div>
    </div>
    <el-empty v-if="!records.length" description="暂无教室申请" :image-size="90" />
    <PagePager
      :page="pager.page"
      :page-size="pager.pageSize"
      :row-count="pager.rowCount"
      :loading="loading"
      @change="(page) => load(false, page)"
    />
  </section>
  <el-dialog
    v-model="createOpen"
    title="新建教室申请"
    width="520px"
    class="approval-dialog"
    :close-on-click-modal="!saving"
    :close-on-press-escape="!saving"
    :show-close="!saving"
  >
    <el-form label-position="top">
      <el-form-item label="申请教室"
        ><el-select v-model="roomId"
          ><el-option
            v-for="r in rooms"
            :key="r.id"
            :value="r.id"
            :label="r.name"
            :disabled="!r.enabled" /></el-select
      ></el-form-item>
      <el-form-item label="使用日期"
        ><el-date-picker v-model="date" type="date" value-format="YYYY-MM-DD" :clearable="false"
      /></el-form-item>
      <div class="form-two-columns">
        <el-form-item label="开始时间"
          ><el-time-picker
            v-model="start"
            format="HH:mm"
            value-format="HH:mm"
            :clearable="false"
            placeholder="开始时间" /></el-form-item
        ><el-form-item label="结束时间"
          ><el-time-picker
            v-model="end"
            format="HH:mm"
            value-format="HH:mm"
            :clearable="false"
            placeholder="结束时间"
        /></el-form-item>
      </div>
      <el-form-item label="申请用途"
        ><el-input
          v-model="purpose"
          type="textarea"
          :rows="3"
          maxlength="1000"
          show-word-limit
          placeholder="说明活动、参与人数和使用需求"
      /></el-form-item>
    </el-form>
    <template #footer
      ><el-button @click="createOpen = false" :disabled="saving">取消</el-button
      ><el-button type="primary" :loading="saving" @click="create">提交申请</el-button></template
    >
  </el-dialog>
  <el-dialog
    :model-value="!!action"
    @close="action = null"
    :title="`${action ? actionTitle[action.type] : ''}申请`"
    width="460px"
    class="approval-dialog"
    :close-on-click-modal="!saving"
    :close-on-press-escape="!saving"
    :show-close="!saving"
  >
    <p>{{ action?.record.classroomName }} · 申请 #{{ action?.record.id }}</p>
    <p class="muted">
      {{ action && approvalStage(action.record) }} · 版本 {{ action?.record.revision }}
    </p>
    <el-alert v-if="actionInvalid" :title="actionInvalid" type="warning" :closable="false" />
    <el-alert
      v-if="feedback"
      :title="feedback.message"
      :description="feedback.hint"
      type="error"
      :closable="false"
    />
    <p class="muted" v-if="action?.type === 'withdraw'">
      撤销将解除本申请产生的占用；正在同步时请等待服务端确认结果。
    </p>
    <el-input
      v-else
      v-model="comment"
      type="textarea"
      :rows="3"
      :disabled="saving"
      :placeholder="
        action?.type === 'resubmit'
          ? '补充活动用途（至少2字）'
          : action?.type === 'approve'
            ? '审批意见（可选）'
            : '填写退回或驳回原因（必填）'
      "
      maxlength="1000"
    />
    <template #footer
      ><el-button @click="action = null" :disabled="saving">取消</el-button
      ><el-button type="primary" :loading="saving" :disabled="!!actionInvalid" @click="execute"
        >确认{{ action ? actionTitle[action.type] : '' }}</el-button
      ></template
    >
  </el-dialog>
  <el-dialog
    v-model="historyOpen"
    @close="closeHistory"
    title="审批记录"
    width="600px"
    class="approval-dialog"
  >
    <p>申请 #{{ historyRecord?.id }} · {{ historyRecord?.classroomName }}</p>
    <p v-if="historyLoading" role="status">正在加载审批记录…</p>
    <el-alert v-else-if="historyError" :title="historyError" type="error" :closable="false" />
    <template v-else
      ><article v-for="entry in history" :key="entry.id" class="approval-history">
        <strong>{{ historyAction[entry.action] ?? entry.action }} · 第{{ entry.step }}级</strong>
        <p class="muted">
          {{ approvalDate(entry.createdAt) }} · {{ entry.actorName }} · 版本 {{ entry.revision }}
        </p>
        <p v-if="entry.comment">{{ entry.comment }}</p>
        <p class="approval-trace">请求编号：{{ entry.requestId || '服务端未记录' }}</p>
      </article>
      <p v-if="!history.length" class="muted">
        暂无审批历史；历史单级申请可能未留存逐级记录。
      </p></template
    >
    <template #footer
      ><el-button v-if="historyError && historyRecord" @click="showHistory(historyRecord)"
        >重试加载</el-button
      ><el-button @click="historyOpen = false">关闭记录</el-button></template
    >
  </el-dialog>
</template>
<style scoped>
.approval-summary {
  display: grid;
  gap: 8px;
  padding: 14px 16px;
  border-radius: 12px;
  background: #f1f5f0;
  color: #3d5947;
}
.approval-summary span {
  font-size: 13px;
}
.workflow-card,
.approval-history,
.approval-feedback,
.approval-trace {
  overflow-wrap: anywhere;
}
.workflow-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.workflow-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}
.approval-history {
  padding: 16px 0;
  border-bottom: 1px solid #e6ebe5;
}
.approval-history p {
  margin: 8px 0;
  white-space: pre-wrap;
}
.approval-trace {
  font-size: 12px;
}
:global(.approval-dialog) {
  max-width: calc(100vw - 24px);
}
@media (min-width: 800px) {
  .approval-summary {
    grid-template-columns: 1fr auto;
  }
  .approval-summary span:last-child {
    grid-column: 1 / -1;
  }
}
</style>
