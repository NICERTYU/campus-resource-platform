<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHeading from '../components/PageHeading.vue'
import PagePager from '../components/PagePager.vue'
import { usePagination } from '../lib/pagination'
import StatusTag from '../components/StatusTag.vue'
import { request, write, errorMessage } from '../lib/api'
import { interval, dateTime, isoHour } from '../lib/time'
import { checkInState, reuseDefaults, reuseValidation, notificationLabel } from '../lib/bookings'
import { reasonLabel } from '../lib/status'
import { useAuth } from '../stores/auth'
import type { Reservation, Violation, ReservationNotification, Notification } from '../lib/types'
const auth = useAuth()
const records = ref<Reservation[]>([]),
  violations = ref<Violation[]>([]),
  notifications = ref<ReservationNotification[]>([]),
  scheduledNotifications = ref<Notification[]>([]),
  loading = ref(false),
  saving = ref(false),
  error = ref(''),
  tab = ref('current'),
  selected = ref<Reservation | null>(null),
  code = ref(''),
  reuseSource = ref<Reservation | null>(null),
  reuseError = ref(''),
  reuseForm = ref({ date: '', start: 9, end: 10 }),
  now = ref(Date.now())
const current = computed(() =>
    records.value.filter((r) => ['PENDING', 'CHECKED_IN'].includes(r.status)),
  ),
  history = computed(() =>
    records.value.filter((r) => !['PENDING', 'CHECKED_IN'].includes(r.status)),
  )
const pager = usePagination()
let loadGeneration = 0,
  timer: ReturnType<typeof setInterval> | undefined
async function load(target = pager.page) {
  if (!Number.isInteger(target) || target < 1) return
  const generation = ++loadGeneration,
    view = tab.value
  loading.value = true
  error.value = ''
  try {
    if (view === 'violations') {
      const result = await request<Violation[]>(`/violations/mine?${pager.query(target)}`)
      if (generation === loadGeneration) {
        violations.value = result
        pager.accept(target, result.length)
      }
    } else if (view === 'scheduledNotifications') {
      const result = await request<Notification[]>(`/notifications/mine?${pager.query(target)}`)
      if (generation === loadGeneration) {
        scheduledNotifications.value = result
        pager.accept(target, result.length)
      }
    } else if (view === 'notifications') {
      const result = await request<ReservationNotification[]>(
        `/reservations/notifications?${pager.query(target)}`,
      )
      if (generation === loadGeneration) {
        notifications.value = result
        pager.accept(target, result.length)
      }
    } else {
      const result = await request<Reservation[]>(
        `/reservations/mine?${pager.query(target)}&statusGroup=${view}`,
      )
      if (generation === loadGeneration) {
        records.value = result
        pager.accept(target, result.length)
      }
    }
  } catch (e) {
    if (generation === loadGeneration) {
      records.value = []
      violations.value = []
      notifications.value = []
      scheduledNotifications.value = []
      pager.rowCount = 0
      error.value = errorMessage(e)
    }
  } finally {
    if (generation === loadGeneration) loading.value = false
  }
}
watch(tab, () => {
  pager.reset()
  records.value = []
  violations.value = []
  notifications.value = []
  scheduledNotifications.value = []
  void load(1)
})
async function cancel(r: Reservation) {
  if (saving.value) return
  saving.value = true
  try {
    await ElMessageBox.confirm(`取消 ${r.classroomName} ${r.seatLabel} 的预约？`, '取消预约', {
      confirmButtonText: '确认取消',
      cancelButtonText: '保留预约',
    })
    await write(`/reservations/${r.id}/cancel`)
    ElMessage.success('预约已取消')
    await load()
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') ElMessage.error(errorMessage(e))
  } finally {
    saving.value = false
  }
}
function openCheckIn(reservation: Reservation) {
  if (saving.value || !checkInState(reservation).canCheckIn) return
  selected.value = reservation
  code.value = ''
}
async function checkIn() {
  if (!selected.value || saving.value) return
  if (!checkInState(selected.value).canCheckIn) {
    ElMessage.warning('签到窗口已变化，请刷新记录后确认')
    return
  }
  if (!/^\d{6}$/.test(code.value.trim())) {
    ElMessage.warning('请输入6位数字的教室当天签到码')
    return
  }
  saving.value = true
  try {
    await write(`/reservations/${selected.value.id}/check-in`, { code: code.value.trim() })
    selected.value = null
    code.value = ''
    ElMessage.success('签到成功，祝学习顺利')
    await load()
  } catch (e) {
    ElMessage.error(errorMessage(e))
  } finally {
    saving.value = false
  }
}
function openReuse(record: Reservation) {
  if (saving.value || !auth.has('STUDENT')) return
  reuseSource.value = record
  reuseError.value = ''
  reuseForm.value = reuseDefaults(record)
}
async function reuse() {
  if (!reuseSource.value || saving.value) return
  const form = { ...reuseForm.value },
    sourceId = reuseSource.value.id
  reuseError.value = reuseValidation(form.date, form.start, form.end)
  if (reuseError.value) return
  saving.value = true
  try {
    await write(`/reservations/${sourceId}/reuse`, {
      startTime: isoHour(form.date, form.start),
      endTime: isoHour(form.date, form.end),
    })
    reuseSource.value = null
    ElMessage.success('历史复用成功，已创建新预约')
    if (tab.value === 'current') await load(1)
    else tab.value = 'current'
  } catch (e) {
    reuseError.value = errorMessage(e)
  } finally {
    saving.value = false
  }
}
onMounted(() => {
  void load()
  timer = setInterval(() => {
    now.value = Date.now()
  }, 1000)
})
onUnmounted(() => {
  clearInterval(timer)
  loadGeneration++
})
</script>
<template>
  <PageHeading
    eyebrow="YOUR STUDY PLAN"
    title="把计划，变成专注。"
    description="管理预约、及时签到，让每一段学习时间都有着落。"
  >
    <el-button @click="load()" :loading="loading" :disabled="saving">刷新记录</el-button>
  </PageHeading>
  <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
  <div class="bookings-summary">
    <div>
      <span class="eyebrow">YOUR RECORDS</span
      ><strong
        >{{
          tab === 'violations'
            ? violations.length
            : tab === 'notifications'
              ? notifications.length
              : tab === 'scheduledNotifications'
                ? scheduledNotifications.length
                : records.length
        }}<small>
          条本页{{
            tab === 'current'
              ? '当前预约'
              : tab === 'history'
                ? '历史预约'
                : tab === 'notifications'
                  ? '站内提醒'
                  : tab === 'scheduledNotifications'
                    ? '通知事件'
                    : '违约记录'
          }}</small
        ></strong
      >
    </div>
    <p>
      请到达教室后，使用当天签到码签到。<br /><span class="muted"
        >开始前15分钟至开始后15分钟内可签到，截止时刻不可签到。</span
      >
    </p>
    <router-link to="/seats" class="text-link">继续寻找座位 ↗</router-link>
  </div>
  <section class="surface" v-loading="loading">
    <el-tabs v-model="tab"
      ><el-tab-pane label="当前预约" name="current" /><el-tab-pane
        label="历史记录"
        name="history" /><el-tab-pane label="站内提醒" name="notifications" /><el-tab-pane
        label="违约记录"
        name="violations" /><el-tab-pane label="通知事件" name="scheduledNotifications"
    /></el-tabs>
    <template v-if="['current', 'history'].includes(tab)">
      <div
        v-for="r in tab === 'current' ? current : history"
        :key="r.id"
        class="booking-row"
        :data-reservation-id="r.id"
      >
        <div class="booking-seat-icon">▦</div>
        <div class="booking-details">
          <div class="booking-title">
            <h3>
              {{ r.classroomName }} <span>· {{ r.seatLabel }}</span>
            </h3>
            <StatusTag
              :status="r.status"
              :label="
                r.status === 'PENDING'
                  ? '待签到'
                  : r.status === 'NO_SHOW'
                    ? '未签到已取消'
                    : undefined
              "
            />
          </div>
          <p>{{ interval(r.startTime, r.endTime) }}</p>
          <p v-if="r.status === 'PENDING'" class="booking-window" role="status">
            {{ checkInState(r, now).label }}
          </p>
          <small v-if="r.status === 'PENDING'" class="muted"
            >签到截止 {{ dateTime(r.checkInDeadline) }} · 以服务端状态为准</small
          >
          <small v-else-if="r.checkedInAt" class="muted"
            >签到时间 {{ dateTime(r.checkedInAt) }}</small
          >
          <small v-if="r.cancelReason" class="muted">{{
            reasonLabel[r.cancelReason] ?? r.cancelReason
          }}</small>
        </div>
        <div class="row-actions" v-if="['PENDING', 'CHECKED_IN'].includes(r.status)">
          <el-button
            v-if="r.status === 'PENDING'"
            type="primary"
            @click="openCheckIn(r)"
            :disabled="saving || !checkInState(r, now).canCheckIn"
            >去签到</el-button
          >
          <el-button @click="cancel(r)" :disabled="saving">取消预约</el-button>
        </div>
        <div class="row-actions" v-else-if="auth.has('STUDENT')">
          <el-button @click="openReuse(r)" :disabled="saving">一键复用</el-button>
        </div>
      </div>
      <el-empty
        v-if="!error && !(tab === 'current' ? current : history).length"
        :description="tab === 'current' ? '还没有当前预约，去发现一个好位置吧' : '暂无历史预约'"
        :image-size="90"
      />
    </template>
    <template v-else-if="tab === 'notifications'">
      <p class="muted">
        仅展示服务端已保存的站内提醒，不代表短信、邮件或微信已送达。点击刷新记录获取最新结果。
      </p>
      <article
        v-for="event in notifications"
        :key="event.id"
        class="m1-notification"
        data-testid="reservation-notification"
      >
        <div>
          <h3>{{ notificationLabel[event.type] }}</h3>
          <p>{{ event.classroomName }} · {{ event.seatLabel }} · 预约 #{{ event.reservationId }}</p>
          <small class="muted"
            >{{ dateTime(event.createdAt) }} · 事件发生时的提醒，当前状态请查看预约</small
          >
        </div>
        <el-tag type="info">站内可查询</el-tag>
      </article>
      <el-empty
        v-if="!error && !notifications.length"
        description="暂无站内提醒"
        :image-size="90"
      />
    </template>
    <template v-else-if="tab === 'scheduledNotifications'">
      <p class="muted">通知事件的处理状态不代表短信、邮件或微信已送达。</p>
      <el-table :data="scheduledNotifications" empty-text="暂无通知事件">
        <el-table-column prop="reservationId" label="预约编号" width="100" />
        <el-table-column label="类型"
          ><template #default="{ row }">{{
            row.type === 'PRE_START_REMINDER'
              ? '开始前提醒'
              : row.type === 'NO_SHOW_WARNING'
                ? '未签到提醒'
                : '取消通知'
          }}</template></el-table-column
        >
        <el-table-column prop="status" label="状态" />
        <el-table-column label="计划时间"
          ><template #default="{ row }">{{ dateTime(row.scheduledAt) }}</template></el-table-column
        >
        <el-table-column label="处理时间"
          ><template #default="{ row }">{{
            row.deliveredAt ? dateTime(row.deliveredAt) : '待处理'
          }}</template></el-table-column
        >
      </el-table>
    </template>
    <template v-else>
      <el-alert
        v-if="violations.length"
        title="未按时签到会形成违约记录，请合理安排预约。"
        type="warning"
        :closable="false"
      />
      <el-table v-if="violations.length" :data="violations"
        ><el-table-column prop="reservationId" label="预约编号" /><el-table-column label="原因"
          ><template #default="{ row }">{{
            reasonLabel[row.reason] ?? row.reason
          }}</template></el-table-column
        ><el-table-column label="记录时间"
          ><template #default="{ row }">{{ dateTime(row.createdAt) }}</template></el-table-column
        ></el-table
      >
      <el-empty
        v-else-if="!error"
        description="暂无违约记录，感谢你珍惜校园资源"
        :image-size="90"
      />
    </template>
    <PagePager
      :page="pager.page"
      :page-size="pager.pageSize"
      :row-count="pager.rowCount"
      :loading="loading"
      @change="(page) => load(page)"
    />
  </section>
  <el-dialog
    :model-value="!!selected"
    @close="selected = null"
    title="到场签到"
    width="430px"
    :close-on-click-modal="!saving"
    :close-on-press-escape="!saving"
    :show-close="!saving"
  >
    <p>{{ selected?.classroomName }} · {{ selected?.seatLabel }}</p>
    <p class="muted">输入教室当天签到码。演示环境初始化当天码为 246810。</p>
    <el-input
      v-model="code"
      placeholder="输入签到码"
      aria-label="签到码"
      maxlength="6"
      :disabled="saving"
      @keyup.enter="checkIn"
    />
    <template #footer
      ><el-button @click="selected = null" :disabled="saving">取消</el-button
      ><el-button type="primary" :loading="saving" @click="checkIn">确认签到</el-button></template
    >
  </el-dialog>
  <el-dialog
    :model-value="!!reuseSource"
    @close="reuseSource = null"
    title="复用历史预约"
    width="480px"
    :close-on-click-modal="!saving"
    :close-on-press-escape="!saving"
    :show-close="!saving"
  >
    <p>{{ reuseSource?.classroomName }} · {{ reuseSource?.seatLabel }}</p>
    <p class="muted">
      保留原座位和时段偏好，请确认新的日期。开放时间、权限、时长及冲突均由服务端重新校验。
    </p>
    <el-alert v-if="reuseError" :title="reuseError" role="alert" type="error" :closable="false" />
    <el-form label-position="top" @submit.prevent="reuse">
      <el-form-item label="新预约日期"
        ><input
          v-model="reuseForm.date"
          type="date"
          aria-label="新预约日期"
          class="m1-date-input"
          :disabled="saving"
      /></el-form-item>
      <div class="form-two-columns">
        <el-form-item label="开始整点"
          ><el-input-number
            v-model="reuseForm.start"
            aria-label="开始整点"
            :min="0"
            :max="23"
            :disabled="saving" /></el-form-item
        ><el-form-item label="结束整点"
          ><el-input-number
            v-model="reuseForm.end"
            aria-label="结束整点"
            :min="1"
            :max="24"
            :disabled="saving"
        /></el-form-item>
      </div>
    </el-form>
    <template #footer
      ><el-button @click="reuseSource = null" :disabled="saving">取消</el-button
      ><el-button type="primary" :loading="saving" @click="reuse">确认复用</el-button></template
    >
  </el-dialog>
</template>
