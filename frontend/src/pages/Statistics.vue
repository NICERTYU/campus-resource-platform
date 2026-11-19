<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import PageHeading from '../components/PageHeading.vue'
import { request, errorMessage } from '../lib/api'
import { campusDate } from '../lib/time'
import { validStatisticsDates } from '../lib/bookings'
import { useAuth } from '../stores/auth'
import type { Classroom, ReservationStatistics } from '../lib/types'
const auth = useAuth(),
  rooms = ref<Classroom[]>([]),
  classroomId = ref<number>(),
  startDate = ref(campusDate(new Date(Date.now() - 6 * 86400000))),
  endDate = ref(campusDate()),
  report = ref<ReservationStatistics | null>(null),
  loading = ref(false),
  error = ref(''),
  roomError = ref('')
let generation = 0,
  disposed = false
const totals = computed(() => {
  const rows = report.value?.rows ?? []
  const sum = (
    key:
      | 'reservationCount'
      | 'cancelledCount'
      | 'noShowCount'
      | 'bookedSeatHours'
      | 'capacitySeatHours',
  ) => rows.reduce((total, row) => total + row[key], 0)
  const capacity = sum('capacitySeatHours')
  return {
    reservations: sum('reservationCount'),
    cancelled: sum('cancelledCount'),
    noShow: sum('noShowCount'),
    utilization: capacity ? sum('bookedSeatHours') / capacity : null,
  }
})
const percent = (value: number | null) =>
  value === null ? '无可用容量' : `${(value * 100).toFixed(1)}%`
const hours = (value: number) => `${value.toFixed(1)} 座位小时`
async function load() {
  const version = ++generation
  report.value = null
  error.value = ''
  if (!auth.isAdmin) {
    loading.value = false
    error.value = '仅管理员可查看运营统计'
    return
  }
  if (!validStatisticsDates(startDate.value, endDate.value)) {
    loading.value = false
    error.value = '请选择首尾有效且不超过31天的统计日期'
    return
  }
  loading.value = true
  const query = new URLSearchParams({ startDate: startDate.value, endDate: endDate.value })
  if (classroomId.value) query.set('classroomId', String(classroomId.value))
  try {
    const result = await request<ReservationStatistics>(`/admin/reservation-statistics?${query}`)
    if (version === generation && auth.isAdmin) report.value = result
  } catch (e) {
    if (version === generation) error.value = errorMessage(e)
  } finally {
    if (version === generation) loading.value = false
  }
}
watch(
  () => auth.isAdmin,
  (allowed) => {
    if (!allowed) {
      generation++
      report.value = null
      rooms.value = []
      loading.value = false
    }
  },
)
onMounted(async () => {
  if (!auth.isAdmin) return
  const reports = load()
  try {
    const result = await request<Classroom[]>('/admin/classrooms')
    if (!disposed && auth.isAdmin) rooms.value = result
  } catch (e) {
    if (!disposed) roomError.value = errorMessage(e)
  }
  await reports
})
onUnmounted(() => {
  disposed = true
  generation++
})
</script>
<template>
  <PageHeading
    eyebrow="STUDY SPACE INSIGHTS"
    title="看见空间的使用。"
    description="按教室与校园日期查看预约占用、取消、未签到和维修影响。"
  />
  <el-alert v-if="!auth.isAdmin" title="仅管理员可查看运营统计" type="error" :closable="false" />
  <template v-else>
    <section class="surface">
      <el-form class="m1-statistics-filters" label-position="top" @submit.prevent="load">
        <el-form-item label="教室"
          ><el-select v-model="classroomId" aria-label="统计教室" clearable placeholder="全部教室"
            ><el-option
              v-for="room in rooms"
              :key="room.id"
              :value="room.id"
              :label="room.name" /></el-select
        ></el-form-item>
        <el-form-item label="开始日期"
          ><input v-model="startDate" type="date" class="m1-date-input" aria-label="统计开始日期"
        /></el-form-item>
        <el-form-item label="结束日期"
          ><input v-model="endDate" type="date" class="m1-date-input" aria-label="统计结束日期"
        /></el-form-item>
        <el-button type="primary" @click="load" :loading="loading">查询统计</el-button>
      </el-form>
      <p class="muted">
        校园时区 ·
        包含首尾日期，最多31天。预约占用率按当前启用座位和开放时间计算，不能当作实际到场率。
      </p>
      <el-alert
        v-if="roomError"
        :title="`教室选项加载失败：${roomError}，仍可查询全部教室`"
        type="warning"
        :closable="false"
      />
      <el-alert v-if="error" :title="error" role="alert" type="error" :closable="false" />
    </section>
    <p v-if="loading" role="status" class="muted">正在查询统计…</p>
    <template v-if="report">
      <p class="muted m1-statistics-range">
        统计日期 {{ report.startDate }} 至 {{ report.endDate }} · {{ report.rows.length }} 间教室
      </p>
      <div class="m1-statistics-summary" data-testid="statistics-summary">
        <article>
          <span>预约数量</span><strong>{{ totals.reservations }}</strong>
        </article>
        <article>
          <span>预约占用率</span><strong>{{ percent(totals.utilization) }}</strong>
        </article>
        <article>
          <span>已取消</span><strong>{{ totals.cancelled }}</strong>
        </article>
        <article>
          <span>未签到</span><strong>{{ totals.noShow }}</strong>
        </article>
      </div>
      <section class="m1-statistics-rooms" aria-label="教室统计结果">
        <article
          v-for="row in report.rows"
          :key="row.classroomId"
          class="surface m1-statistics-room"
          data-testid="statistics-room"
        >
          <h2>{{ row.classroomName }}</h2>
          <dl>
            <div>
              <dt>预约数量</dt>
              <dd>{{ row.reservationCount }}</dd>
            </div>
            <div>
              <dt>预约占用率</dt>
              <dd>{{ percent(row.utilization) }}</dd>
            </div>
            <div>
              <dt>已取消 / 未签到</dt>
              <dd>{{ row.cancelledCount }} / {{ row.noShowCount }}</dd>
            </div>
            <div>
              <dt>维修取消预约</dt>
              <dd>{{ row.maintenanceCancelledCount }}</dd>
            </div>
            <div>
              <dt>预约占用时长</dt>
              <dd>{{ hours(row.bookedSeatHours) }}</dd>
            </div>
            <div>
              <dt>开放容量</dt>
              <dd>{{ hours(row.capacitySeatHours) }}</dd>
            </div>
            <div>
              <dt>维修影响</dt>
              <dd>{{ hours(row.maintenanceSeatHours) }}</dd>
            </div>
          </dl>
        </article>
      </section>
      <el-empty v-if="!report.rows.length" description="所选范围暂无教室统计" />
      <p class="muted">
        数量按预约开始日归属；时长裁剪到查询日期和每日开放区间。维修影响对重叠来源去重，仅统计已发生区间。历史配置未保存快照，统计按当前配置计算；维修取消已包含在“已取消”中。
      </p>
    </template>
  </template>
</template>
