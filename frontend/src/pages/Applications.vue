<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageHeading from '../components/PageHeading.vue'
import PagePager from '../components/PagePager.vue'
import { usePagination } from '../lib/pagination'
import StatusTag from '../components/StatusTag.vue'
import { useAuth } from '../stores/auth'
import { canWithdraw } from '../lib/permissions'
import { request, write, errorMessage } from '../lib/api'
import { interval, nextSlot, isoMinute, validTimes } from '../lib/time'
import type { Application, Classroom } from '../lib/types'
const auth = useAuth(),
  records = ref<Application[]>([]),
  rooms = ref<Classroom[]>([]),
  loading = ref(false),
  saving = ref(false),
  error = ref(''),
  createOpen = ref(false),
  roomId = ref<number>(),
  date = ref(nextSlot().date),
  start = ref(`${String(nextSlot().hour).padStart(2, '0')}:00`),
  end = ref(`${String(nextSlot().hour + 1).padStart(2, '0')}:00`),
  purpose = ref(''),
  action = ref<{ record: Application; type: string } | null>(null),
  comment = ref('')
const hours = Array.from({ length: 25 }, (_, i) => i)
let poll: ReturnType<typeof setInterval> | undefined
const pager = usePagination()
let loadGeneration = 0
async function load(silent = false, target = pager.page) {
  if (!Number.isInteger(target) || target < 1) return
  const generation = ++loadGeneration
  if (!silent) loading.value = true
  error.value = ''
  try {
    const result = await request<Application[]>(`/classroom-applications?${pager.query(target)}`)
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
  if (
    !roomId.value ||
    !date.value ||
    !validTimes(start.value, end.value) ||
    purpose.value.trim().length < 2
  ) {
    ElMessage.warning('请选择教室、有效时段并填写申请用途（至少2字）')
    return
  }
  saving.value = true
  try {
    await write('/classroom-applications', {
      classroomId: roomId.value,
      startTime: isoMinute(date.value, start.value),
      endTime: isoMinute(date.value, end.value),
      purpose: purpose.value.trim(),
    })
    createOpen.value = false
    purpose.value = ''
    ElMessage.success('申请已提交')
    await load(false, 1)
  } catch (e) {
    ElMessage.error(errorMessage(e))
  } finally {
    saving.value = false
  }
}
function openAction(record: Application, type: string) {
  action.value = { record, type }
  comment.value = ''
}
async function execute() {
  if (!action.value) return
  if (action.value.type === 'reject' && !comment.value.trim()) {
    ElMessage.warning('请填写驳回原因')
    return
  }
  saving.value = true
  try {
    await write(
      `/classroom-applications/${action.value.record.id}/${action.value.type}`,
      action.value.type === 'withdraw' ? {} : { comment: comment.value.trim() },
    )
    action.value = null
    ElMessage.success('操作已提交')
    await load()
  } catch (e) {
    ElMessage.error(errorMessage(e))
  } finally {
    saving.value = false
  }
}
onMounted(async () => {
  await load()
  try {
    rooms.value = await request<Classroom[]>('/classrooms')
    roomId.value = rooms.value.find((r) => r.enabled)?.id
  } catch (e) {
    error.value = errorMessage(e)
  }
  poll = setInterval(() => {
    if (!loading.value && records.value.some((r) => r.status === 'PROCESSING')) void load(true)
  }, 5000)
})
onUnmounted(() => clearInterval(poll))
</script>
<template>
  <PageHeading
    eyebrow="ROOM APPLICATIONS"
    :title="auth.isAdmin ? '让每场活动，妥善落座。' : '为下一场活动，申请空间。'"
    :description="
      auth.isAdmin
        ? '审核整室使用需求；审批通过后，该时段全部座位将被占用。'
        : '提交申请后等待管理员审核，审批通过才会形成教室占用。'
    "
    ><el-button @click="load()" :loading="loading">刷新</el-button
    ><el-button
      v-if="auth.has('APPLICANT') || auth.isAdmin"
      type="primary"
      @click="createOpen = true"
      >＋ 新建申请</el-button
    ></PageHeading
  ><el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
  <section class="surface" v-loading="loading">
    <div class="section-kicker">
      {{ auth.isAdmin ? '申请审批台' : '我的教室申请' }}
      <small>本页 {{ records.length }} 条记录</small>
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
      <p class="muted">
        申请人：{{ r.applicantName
        }}<template v-if="r.comment"> · 审批意见：{{ r.comment }}</template>
      </p>
      <el-alert
        v-if="r.pendingAction"
        title="正在与资源服务同步，请稍后刷新查看结果。"
        type="info"
        :closable="false"
      /><el-alert v-if="r.lastError" :title="r.lastError" type="warning" :closable="false" />
      <div class="workflow-actions">
        <template v-if="auth.isAdmin && r.status === 'PENDING'"
          ><el-button type="primary" @click="openAction(r, 'approve')">通过申请</el-button
          ><el-button @click="openAction(r, 'reject')">驳回</el-button></template
        ><el-button v-if="canWithdraw(r, auth.user)" @click="openAction(r, 'withdraw')"
          >撤销申请</el-button
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
  <el-dialog v-model="createOpen" title="新建教室申请" width="520px"
    ><el-form label-position="top"
      ><el-form-item label="申请教室"
        ><el-select v-model="roomId"
          ><el-option
            v-for="r in rooms"
            :key="r.id"
            :value="r.id"
            :label="r.name"
            :disabled="!r.enabled" /></el-select></el-form-item
      ><el-form-item label="使用日期"
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
          placeholder="说明活动、参与人数和使用需求" /></el-form-item></el-form
    ><template #footer
      ><el-button @click="createOpen = false" :disabled="saving">取消</el-button
      ><el-button type="primary" :loading="saving" @click="create">提交申请</el-button></template
    ></el-dialog
  ><el-dialog
    :model-value="!!action"
    @close="action = null"
    :title="
      action?.type === 'approve' ? '通过申请' : action?.type === 'reject' ? '驳回申请' : '撤销申请'
    "
    width="460px"
    ><p>{{ action?.record.classroomName }} · 申请 #{{ action?.record.id }}</p>
    <p class="muted" v-if="action?.type === 'withdraw'">
      已通过的申请撤销后，将解除该申请产生的教室占用。
    </p>
    <el-input
      v-else
      v-model="comment"
      type="textarea"
      :rows="3"
      :placeholder="action?.type === 'reject' ? '填写驳回原因' : '审批意见（可选）'"
      maxlength="1000"
    /><template #footer
      ><el-button @click="action = null" :disabled="saving">取消</el-button
      ><el-button type="primary" :loading="saving" @click="execute"
        >确认{{
          action?.type === 'approve' ? '通过' : action?.type === 'reject' ? '驳回' : '撤销'
        }}</el-button
      ></template
    ></el-dialog
  >
</template>
