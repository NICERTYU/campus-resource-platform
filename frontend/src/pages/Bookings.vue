<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHeading from '../components/PageHeading.vue'
import PagePager from '../components/PagePager.vue'
import { usePagination } from '../lib/pagination'
import StatusTag from '../components/StatusTag.vue'
import { request, write, errorMessage } from '../lib/api'
import { interval, dateTime } from '../lib/time'
import { reasonLabel } from '../lib/status'
import type { Reservation, Violation } from '../lib/types'
const records = ref<Reservation[]>([]),
  violations = ref<Violation[]>([]),
  loading = ref(false),
  saving = ref(false),
  error = ref(''),
  tab = ref('current'),
  selected = ref<Reservation | null>(null),
  code = ref('')
const current = computed(() =>
    records.value.filter((r) => ['PENDING', 'CHECKED_IN'].includes(r.status)),
  ),
  history = computed(() =>
    records.value.filter((r) => !['PENDING', 'CHECKED_IN'].includes(r.status)),
  )
const pager = usePagination()
let loadGeneration = 0
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
    if (generation === loadGeneration) error.value = errorMessage(e)
  } finally {
    if (generation === loadGeneration) loading.value = false
  }
}
watch(tab, () => {
  pager.reset()
  records.value = []
  violations.value = []
  void load(1)
})
async function cancel(r: Reservation) {
  try {
    await ElMessageBox.confirm(`取消 ${r.classroomName} ${r.seatLabel} 的预约？`, '取消预约', {
      confirmButtonText: '确认取消',
      cancelButtonText: '保留预约',
    })
    saving.value = true
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
  selected.value = reservation
  code.value = ''
}
async function checkIn() {
  if (!selected.value) return
  if (!code.value.trim()) {
    ElMessage.warning('请输入教室当天签到码')
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
onMounted(() => load())
</script>
<template>
  <PageHeading
    eyebrow="YOUR STUDY PLAN"
    title="把计划，变成专注。"
    description="管理预约、及时签到，让每一段学习时间都有着落。"
    ><el-button @click="load()" :loading="loading">刷新记录</el-button></PageHeading
  ><el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
  <div class="bookings-summary">
    <div>
      <span class="eyebrow">YOUR RECORDS</span
      ><strong
        >{{ tab === 'violations' ? violations.length : records.length
        }}<small>{{
          tab === 'current'
            ? ' 个本页当前预约'
            : tab === 'history'
              ? ' 个本页历史预约'
              : ' 条本页违约记录'
        }}</small></strong
      >
    </div>
    <p>
      请到达教室后，使用当天签到码签到。<br /><span class="muted"
        >开始前15分钟至开始后15分钟内可签到。</span
      >
    </p>
    <router-link to="/seats" class="text-link">继续寻找座位 ↗</router-link>
  </div>
  <section class="surface" v-loading="loading">
    <el-tabs v-model="tab"
      ><el-tab-pane label="当前预约" name="current" /><el-tab-pane
        label="历史记录"
        name="history" /><el-tab-pane label="违约记录" name="violations" /></el-tabs
    ><template v-if="tab !== 'violations'"
      ><div v-for="r in tab === 'current' ? current : history" :key="r.id" class="booking-row">
        <div class="booking-seat-icon">▦</div>
        <div class="booking-details">
          <div class="booking-title">
            <h3>
              {{ r.classroomName }} <span>· {{ r.seatLabel }}</span>
            </h3>
            <StatusTag :status="r.status" :label="r.status === 'PENDING' ? '待签到' : undefined" />
          </div>
          <p>{{ interval(r.startTime, r.endTime) }}</p>
          <small v-if="r.status === 'PENDING'" class="muted"
            >签到截止 {{ dateTime(r.checkInDeadline) }}</small
          ><small v-else-if="r.checkedInAt" class="muted"
            >签到时间 {{ dateTime(r.checkedInAt) }}</small
          ><small v-if="r.cancelReason" class="muted">{{
            reasonLabel[r.cancelReason] ?? r.cancelReason
          }}</small>
        </div>
        <div class="row-actions" v-if="['PENDING', 'CHECKED_IN'].includes(r.status)">
          <el-button v-if="r.status === 'PENDING'" type="primary" @click="openCheckIn(r)"
            >去签到</el-button
          ><el-button @click="cancel(r)" :disabled="saving">取消预约</el-button>
        </div>
      </div>
      <el-empty
        v-if="!(tab === 'current' ? current : history).length"
        :description="tab === 'current' ? '还没有当前预约，去发现一个好位置吧' : '暂无历史预约'"
        :image-size="90" /></template
    ><template v-else
      ><el-alert
        v-if="violations.length"
        title="未按时签到会形成违约记录，请合理安排预约。"
        type="warning"
        :closable="false" /><el-table v-if="violations.length" :data="violations"
        ><el-table-column prop="reservationId" label="预约编号" /><el-table-column label="原因"
          ><template #default="{ row }">{{
            reasonLabel[row.reason] ?? row.reason
          }}</template></el-table-column
        ><el-table-column label="记录时间"
          ><template #default="{ row }">{{ dateTime(row.createdAt) }}</template></el-table-column
        ></el-table
      ><el-empty v-else description="暂无违约记录，感谢你珍惜校园资源" :image-size="90"
    /></template>
    <PagePager
      :page="pager.page"
      :page-size="pager.pageSize"
      :row-count="pager.rowCount"
      :loading="loading"
      @change="(page) => load(page)"
    />
  </section>
  <el-dialog :model-value="!!selected" @close="selected = null" title="到场签到" width="430px"
    ><p>{{ selected?.classroomName }} · {{ selected?.seatLabel }}</p>
    <p class="muted">输入教室当天签到码。演示环境初始化当天码为 246810。</p>
    <el-input
      v-model="code"
      placeholder="输入签到码"
      aria-label="签到码"
      maxlength="32"
      @keyup.enter="checkIn"
    /><template #footer
      ><el-button @click="selected = null" :disabled="saving">取消</el-button
      ><el-button type="primary" :loading="saving" @click="checkIn">确认签到</el-button></template
    ></el-dialog
  >
</template>
