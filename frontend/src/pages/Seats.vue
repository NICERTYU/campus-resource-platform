<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageHeading from '../components/PageHeading.vue'
import { request, write, errorMessage } from '../lib/api'
import type { Classroom, Seat, Reservation } from '../lib/types'
import { nextSlot, isoHour, validHours } from '../lib/time'
import { restrictionLabel } from '../lib/status'
import { useAuth } from '../stores/auth'
const auth = useAuth(),
  classrooms = ref<Classroom[]>([]),
  seats = ref<Seat[]>([]),
  classroomId = ref<number>(),
  date = ref(nextSlot().date),
  start = ref(nextSlot().hour),
  end = ref(nextSlot().hour + 1),
  power = ref(false),
  loading = ref(false),
  saving = ref(false),
  error = ref(''),
  searched = ref(false),
  selected = ref<Seat | null>(null)
const room = computed(() => classrooms.value.find((c) => c.id === classroomId.value)),
  available = computed(() => seats.value.filter((s) => s.available).length),
  total = computed(() => seats.value.length)
const hours = Array.from({ length: 25 }, (_, i) => i)
let searchGeneration = 0
async function search() {
  error.value = ''
  selected.value = null
  if (!classroomId.value || !date.value || !validHours(start.value, end.value)) {
    error.value = '请选择教室、日期和有效的整点时段'
    return
  }
  const generation = ++searchGeneration
  loading.value = true
  try {
    const query = new URLSearchParams({
      classroomId: String(classroomId.value),
      startTime: isoHour(date.value, start.value),
      endTime: isoHour(date.value, end.value),
      ...(power.value ? { hasPower: 'true' } : {}),
    })
    const result = await request<Seat[]>(`/seats/availability?${query}`)
    if (generation === searchGeneration) {
      seats.value = result
      searched.value = true
    }
  } catch (e) {
    if (generation === searchGeneration) {
      error.value = errorMessage(e)
      seats.value = []
      searched.value = false
    }
  } finally {
    if (generation === searchGeneration) loading.value = false
  }
}
function invalidate() {
  searchGeneration++
  searched.value = false
  seats.value = []
  selected.value = null
  loading.value = false
}
function reason(seat: Seat) {
  return (
    seat.restrictionTypes?.map((t) => restrictionLabel[t] ?? t).join(' · ') || '当前时段不可预约'
  )
}
async function reserve() {
  if (!selected.value) return
  saving.value = true
  try {
    await write<Reservation>('/reservations', {
      seatId: selected.value.id,
      startTime: isoHour(date.value, start.value),
      endTime: isoHour(date.value, end.value),
    })
    selected.value = null
    ElMessage.success('预约成功，请在签到窗口内输入教室签到码')
    await search()
  } catch (e) {
    ElMessage.error(errorMessage(e))
  } finally {
    saving.value = false
  }
}
onMounted(async () => {
  loading.value = true
  try {
    classrooms.value = await request<Classroom[]>('/classrooms')
    classroomId.value = classrooms.value.find((c) => c.enabled)?.id
    if (classroomId.value) await search()
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
})
</script>
<template>
  <PageHeading
    eyebrow="FIND YOUR FOCUS"
    title="今天，坐在哪里？"
    description="选一个安静的位置，把时间留给重要的事。"
    ><router-link class="text-link" to="/bookings">查看我的预约 ↗</router-link></PageHeading
  >
  <section class="search-card">
    <div class="section-kicker">
      <span>01</span> 选择学习计划 <small>预约以整点小时为单位</small>
    </div>
    <el-form class="search-form" label-position="top" @submit.prevent="search"
      ><el-form-item label="自习空间"
        ><el-select v-model="classroomId" placeholder="选择教室" @change="invalidate"
          ><el-option
            v-for="c in classrooms"
            :key="c.id"
            :value="c.id"
            :label="`${c.name} · ${c.building}`"
            :disabled="!c.enabled" /></el-select></el-form-item
      ><el-form-item label="学习日期"
        ><el-date-picker
          v-model="date"
          type="date"
          value-format="YYYY-MM-DD"
          format="YYYY-MM-DD"
          :clearable="false"
          @change="invalidate" /></el-form-item
      ><el-form-item label="开始时间"
        ><el-select v-model="start" @change="invalidate"
          ><el-option
            v-for="h in hours.slice(0, -1)"
            :key="h"
            :value="h"
            :label="`${String(h).padStart(2, '0')}:00`" /></el-select></el-form-item
      ><el-form-item label="结束时间"
        ><el-select v-model="end" @change="invalidate"
          ><el-option
            v-for="h in hours.slice(1)"
            :key="h"
            :value="h"
            :label="`${String(h).padStart(2, '0')}:00`"
            :disabled="h <= start" /></el-select></el-form-item
      ><el-form-item label="座位偏好"
        ><el-checkbox v-model="power" @change="invalidate">需要插座</el-checkbox></el-form-item
      ><el-button type="primary" native-type="submit" :loading="loading" class="search-button"
        >查询座位 →</el-button
      ></el-form
    >
  </section>
  <el-alert
    v-if="error"
    :title="error"
    type="error"
    :closable="false"
    show-icon
    class="page-alert"
  />
  <section class="space-overview" v-if="room">
    <div class="room-intro">
      <span class="space-icon">▦</span>
      <div>
        <span class="eyebrow">{{ room.department ? '院系专属自习室' : '校园共享空间' }}</span>
        <h2>{{ room.name }}</h2>
        <p>
          {{ room.building }} · {{ room.floor }} 层 <span class="middle-dot">·</span> 开放
          {{ room.openTime.slice(0, 5) }}–{{ room.closeTime.slice(0, 5) }}
        </p>
      </div>
    </div>
    <div class="availability-count" v-if="searched">
      <strong
        >{{ available }}<small> / {{ total }}</small></strong
      ><span>{{ power ? '符合插座条件的可用座位' : '当前时段可用座位' }}</span>
    </div>
    <div v-else class="muted">查询后显示实时可用性</div>
  </section>
  <section class="seat-map-card" v-loading="loading">
    <div class="seat-map-heading">
      <div>
        <span class="section-kicker"><span>02</span> 挑选你的座位</span>
        <p class="muted">点击可用座位，确认预约。查询结果不预留座位。</p>
      </div>
      <div class="seat-legend">
        <span><i class="legend-available"></i> 可预约</span
        ><span><i class="legend-unavailable"></i> 不可预约</span
        ><span class="power-legend">ϟ 有插座</span>
      </div>
    </div>
    <template v-if="searched && seats.length"
      ><div class="classroom-front">讲 台 / 教 室 前 方</div>
      <div class="seat-grid">
        <el-tooltip
          v-for="seat in seats"
          :key="seat.id"
          :content="
            seat.available ? `${seat.label}${seat.hasPower ? ' · 带插座' : ''}` : reason(seat)
          "
          placement="top"
          ><button
            class="seat"
            :class="{ 'seat-unavailable': !seat.available }"
            :disabled="!seat.available || !auth.has('STUDENT')"
            :aria-label="`座位 ${seat.label}${seat.available ? ' 可预约' : ' 不可预约'}`"
            @click="selected = seat"
          >
            <span>{{ seat.label }}</span
            ><small>{{ seat.hasPower ? 'ϟ' : '—' }}</small>
          </button></el-tooltip
        >
      </div>
      <div class="classroom-back">入口 <span>↗</span></div></template
    ><el-empty
      v-else
      :description="searched ? '没有符合条件的座位，请调整筛选条件' : '选择学习时段后查询座位'"
      :image-size="80"
    />
    <div class="seat-map-note">
      <span>ⓘ</span>
      <p>
        {{
          auth.has('STUDENT')
            ? '签到窗口为开始前15分钟至开始后15分钟，截止时刻不接受签到。未签到将释放座位并登记违约。'
            : '当前账号可查询空间状态；预约座位需要学生角色。'
        }}<br />空间访问和开放时间以服务端校验结果为准。
      </p>
    </div>
  </section>
  <el-dialog :model-value="!!selected" @close="selected = null" title="确认座位预约" width="440px"
    ><div class="reservation-confirm">
      <span class="eyebrow">YOUR NEXT FOCUS SESSION</span>
      <h2>{{ room?.name }} · {{ selected?.label }}</h2>
      <p>
        {{ date }}　{{ String(start).padStart(2, '0') }}:00 — {{ String(end).padStart(2, '0') }}:00
      </p>
      <p class="muted">{{ end - start }} 小时{{ selected?.hasPower ? ' · 带插座' : '' }}</p>
      <el-alert title="请按时到场签到，合理使用校园资源。" type="info" :closable="false" />
    </div>
    <template #footer
      ><el-button @click="selected = null" :disabled="saving">再想一下</el-button
      ><el-button type="primary" :loading="saving" @click="reserve">确认预约</el-button></template
    ></el-dialog
  >
</template>
