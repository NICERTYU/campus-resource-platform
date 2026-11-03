<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageHeading from '../components/PageHeading.vue'
import PagePager from '../components/PagePager.vue'
import { usePagination } from '../lib/pagination'
import StatusTag from '../components/StatusTag.vue'
import { useAuth } from '../stores/auth'
import { request, write, errorMessage } from '../lib/api'
import { dateTime } from '../lib/time'
import type { Repair, RepairHistory, RepairEscalation, Classroom, User } from '../lib/types'
const escalations = ref<RepairEscalation[]>([])
const escalationError = ref('')
let loadingEscalations = false
async function loadEscalations() {
  if (!auth.isAdmin || loadingEscalations) return
  loadingEscalations = true
  try {
    escalations.value = await request<RepairEscalation[]>(
      '/repair-orders/escalations?page=1&pageSize=10',
    )
    escalationError.value = ''
  } catch (e) {
    escalationError.value = errorMessage(e)
  } finally {
    loadingEscalations = false
  }
}
async function refresh() {
  await Promise.all([load(), loadEscalations()])
}
const histories = ref<Record<number, RepairHistory[]>>({})
async function showHistory(id: number) {
  try {
    histories.value[id] = await request<RepairHistory[]>(`/repair-orders/${id}/history`)
  } catch (e) {
    ElMessage.error(errorMessage(e))
  }
}
const auth = useAuth(),
  records = ref<Repair[]>([]),
  rooms = ref<Classroom[]>([]),
  technicians = ref<User[]>([]),
  loading = ref(false),
  saving = ref(false),
  error = ref(''),
  createOpen = ref(false),
  roomId = ref<number>(),
  description = ref(''),
  action = ref<{ record: Repair; type: string } | null>(null),
  text = ref(''),
  technicianId = ref<number>()
let poll: ReturnType<typeof setInterval> | undefined
const pager = usePagination()
let loadGeneration = 0
async function load(silent = false, target = pager.page) {
  if (!Number.isInteger(target) || target < 1) return
  const generation = ++loadGeneration
  if (!silent) loading.value = true
  error.value = ''
  try {
    const result = await request<Repair[]>(`/repair-orders?${pager.query(target)}`)
    if (generation === loadGeneration) {
      records.value = result
      pager.accept(target, result.length)
    }
  } catch (e) {
    if (generation === loadGeneration) error.value = errorMessage(e)
  } finally {
    if (generation === loadGeneration) loading.value = false
  }
}
async function create() {
  if (!roomId.value || description.value.trim().length < 2) {
    ElMessage.warning('请选择教室并描述故障（至少2字）')
    return
  }
  saving.value = true
  try {
    await write('/repair-orders', {
      classroomId: roomId.value,
      description: description.value.trim(),
    })
    createOpen.value = false
    description.value = ''
    ElMessage.success('报修已提交')
    await load(false, 1)
  } catch (e) {
    ElMessage.error(errorMessage(e))
  } finally {
    saving.value = false
  }
}
function openAction(record: Repair, type: string) {
  action.value = { record, type }
  text.value = ''
  technicianId.value = technicians.value[0]?.id
}
async function execute() {
  if (!action.value) return
  const type = action.value.type
  if (type === 'assign' && !technicianId.value) {
    ElMessage.warning('请选择维修人员')
    return
  }
  if (type === 'assign' && action.value.record.status === 'ASSIGNED' && !text.value.trim()) {
    ElMessage.warning('请填写改派原因')
    return
  }
  if (['complete', 'return'].includes(type) && !text.value.trim()) {
    ElMessage.warning(type === 'return' ? '请填写返修原因' : '请填写完工说明')
    return
  }
  saving.value = true
  try {
    const body =
      type === 'assign'
        ? {
            technicianId: technicianId.value,
            ...(action.value.record.status === 'ASSIGNED'
              ? {
                  reason: text.value.trim(),
                  expectedTechnicianId: action.value.record.technicianId,
                }
              : {}),
          }
        : type === 'start'
          ? {}
          : type === 'complete'
            ? { note: text.value.trim() }
            : { comment: text.value.trim() }
    await write(`/repair-orders/${action.value.record.id}/${type}`, body)
    action.value = null
    ElMessage.success('工单操作已提交')
    await load()
  } catch (e) {
    ElMessage.error(errorMessage(e))
  } finally {
    saving.value = false
  }
}
const actionName: Record<string, string> = {
  assign: '分派维修人员',
  start: '开始维修',
  complete: '提交完工',
  accept: '验收通过',
  return: '退回维修',
}
onMounted(async () => {
  await load()
  await loadEscalations()
  try {
    rooms.value = await request<Classroom[]>('/classrooms')
    roomId.value = rooms.value[0]?.id
    if (auth.isAdmin)
      technicians.value = (await request<User[]>('/admin/users?role=TECHNICIAN')).filter((u) =>
        u.roles.includes('TECHNICIAN'),
      )
  } catch (e) {
    error.value = errorMessage(e)
  }
  poll = setInterval(() => {
    if (!loading.value && records.value.some((r) => r.status === 'PROCESSING')) void load(true)
    void loadEscalations()
  }, 5000)
})
onUnmounted(() => clearInterval(poll))
</script>
<template>
  <PageHeading
    eyebrow="CARE FOR OUR SPACES"
    title="一起，让校园更好用。"
    :description="
      auth.isAdmin
        ? '派单、跟进与验收。维修中的教室会自动暂停座位预约。'
        : auth.has('TECHNICIAN')
          ? '查看分派给你的工单，更新维修进度并提交完工。'
          : '遇到空间故障？告诉我们，让问题尽快得到处理。'
    "
    ><el-button @click="refresh" :loading="loading">刷新</el-button
    ><el-button type="primary" @click="createOpen = true">＋ 上报故障</el-button></PageHeading
  >
  <div class="info-banner">
    <span>⌁</span>
    <p>
      工单开始维修后整室下架，提交完工后仍保留限制，管理员验收通过后才恢复。<br /><small
        >空间存在其他有效占用或维修时，仍可能无法预约。</small
      >
    </p>
  </div>
  <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
  <el-alert
    v-if="escalationError"
    :title="'超时提醒加载失败：' + escalationError"
    type="warning"
    :closable="false"
  />
  <section v-if="auth.isAdmin && escalations.length" class="surface" aria-label="工单超时升级提醒">
    <h3>超时升级提醒</h3>
    <p class="muted">最近10条管理员站内提醒。</p>
    <p v-for="event in escalations" :key="event.id">
      工单 #{{ event.repairId }} 已超处理期限（{{ dateTime(event.deadline) }}），请跟进。
      <small>升级时间：{{ dateTime(event.escalatedAt) }}</small>
    </p>
  </section>
  <section class="surface" v-loading="loading">
    <div class="section-kicker">
      {{ auth.isAdmin ? '报修工单台' : auth.has('TECHNICIAN') ? '我的维修任务' : '我的报修记录' }}
      <small>本页 {{ records.length }} 条记录</small>
    </div>
    <div v-for="r in records" :key="r.id" class="workflow-card">
      <div class="workflow-top">
        <div>
          <span class="record-number">工单 #{{ r.id }}</span>
          <h3>{{ r.classroomName }}</h3>
        </div>
        <StatusTag :status="r.status" />
      </div>
      <p class="workflow-description">{{ r.description }}</p>
      <div class="repair-meta">
        <span>上报人：{{ r.reporterName }}</span
        ><span>维修人员：{{ r.technicianName || '等待分派' }}</span>
      </div>
      <p class="muted" v-if="r.note">完工说明：{{ r.note }}</p>
      <p class="muted" v-if="r.comment">验收意见：{{ r.comment }}</p>
      <p class="muted" v-if="r.repairDeadline">处理期限：{{ dateTime(r.repairDeadline) }}</p>
      <el-alert
        v-if="r.pendingAction"
        title="正在同步资源状态，请稍后查看。"
        type="info"
        :closable="false"
      /><el-alert v-if="r.lastError" :title="r.lastError" type="warning" :closable="false" />
      <div class="workflow-actions">
        <el-button @click="showHistory(r.id)">派单与返修记录</el-button>
        <el-button
          v-if="auth.isAdmin && ['REPORTED', 'ASSIGNED'].includes(r.status)"
          type="primary"
          @click="openAction(r, 'assign')"
          >{{ r.status === 'ASSIGNED' ? '改派' : '派单' }}</el-button
        ><el-button
          v-if="
            auth.has('TECHNICIAN') && r.technicianId === auth.user?.id && r.status === 'ASSIGNED'
          "
          type="primary"
          @click="openAction(r, 'start')"
          >开始维修</el-button
        ><el-button
          v-if="
            auth.has('TECHNICIAN') && r.technicianId === auth.user?.id && r.status === 'IN_PROGRESS'
          "
          type="primary"
          @click="openAction(r, 'complete')"
          >提交完工</el-button
        ><template v-if="auth.isAdmin && r.status === 'AWAITING_ACCEPTANCE'"
          ><el-button type="primary" @click="openAction(r, 'accept')">验收通过</el-button
          ><el-button @click="openAction(r, 'return')">退回维修</el-button></template
        >
      </div>
      <p v-for="h in histories[r.id]" :key="h.id" class="muted">
        {{ h.actorName }} ·
        {{ h.action === 'RETURN' ? '退回维修' : h.action === 'REASSIGN' ? '改派' : '派单' }} ·
        {{ dateTime(h.createdAt) }}
        <span v-if="h.previousTechnicianId"> · 原维修人员 #{{ h.previousTechnicianId }}</span>
        <span v-if="h.technicianId"> · 维修人员 #{{ h.technicianId }}</span>
        <span v-if="h.reason"> · {{ h.reason }}</span>
      </p>
    </div>
    <el-empty v-if="!records.length" description="暂无报修工单" :image-size="90" />
    <PagePager
      :page="pager.page"
      :page-size="pager.pageSize"
      :row-count="pager.rowCount"
      :loading="loading"
      @change="(page) => load(false, page)"
    />
  </section>
  <el-dialog v-model="createOpen" title="上报教室故障" width="500px"
    ><el-form label-position="top"
      ><el-form-item label="故障所在教室"
        ><el-select v-model="roomId"
          ><el-option
            v-for="r in rooms"
            :key="r.id"
            :value="r.id"
            :label="`${r.name} · ${r.building}`" /></el-select></el-form-item
      ><el-form-item label="故障描述"
        ><el-input
          v-model="description"
          type="textarea"
          :rows="4"
          maxlength="2000"
          show-word-limit
          placeholder="说明具体位置、故障现象和影响，便于维修人员处理" /></el-form-item></el-form
    ><template #footer
      ><el-button @click="createOpen = false" :disabled="saving">取消</el-button
      ><el-button type="primary" :loading="saving" @click="create">提交报修</el-button></template
    ></el-dialog
  ><el-dialog
    :model-value="!!action"
    @close="action = null"
    :title="actionName[action?.type ?? '']"
    width="470px"
    ><p>{{ action?.record.classroomName }} · 工单 #{{ action?.record.id }}</p>
    <el-select v-if="action?.type === 'assign'" v-model="technicianId" placeholder="选择维修人员"
      ><el-option v-for="u in technicians" :key="u.id" :value="u.id" :label="u.displayName"
    /></el-select>
    <el-input
      v-if="action?.type === 'assign' && action.record.status === 'ASSIGNED'"
      v-model="text"
      type="textarea"
      maxlength="1000"
      placeholder="填写改派原因"
    />
    <p v-else-if="action?.type === 'start'" class="muted">
      开始维修后该教室整体暂停预约。已有记录保留，维修期间无法签到。
    </p>
    <el-input
      v-else-if="action?.type !== 'assign'"
      v-model="text"
      type="textarea"
      :rows="3"
      :maxlength="action?.type === 'complete' ? 2000 : 1000"
      :placeholder="
        action?.type === 'complete'
          ? '填写维修结果和完工说明'
          : action?.type === 'return'
            ? '填写返修原因'
            : '验收意见（可选）'
      "
    /><template #footer
      ><el-button @click="action = null" :disabled="saving">取消</el-button
      ><el-button type="primary" :loading="saving" @click="execute"
        >确认{{ actionName[action?.type ?? ''] }}</el-button
      ></template
    ></el-dialog
  >
</template>
