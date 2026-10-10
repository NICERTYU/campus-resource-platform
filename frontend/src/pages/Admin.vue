<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import PageHeading from '../components/PageHeading.vue'
import PagePager from '../components/PagePager.vue'
import { usePagination } from '../lib/pagination'
import { request, write, errorMessage } from '../lib/api'
import { campusDate, dateTime } from '../lib/time'
import { actionLabel } from '../lib/status'
import type { Classroom, Seat, Audit } from '../lib/types'
const tab = ref('spaces'),
  rooms = ref<Classroom[]>([]),
  seats = ref<Seat[]>([]),
  roomId = ref<number>(),
  logs = ref<Audit[]>([]),
  codes = ref<
    { classroomId: number; classroomName?: string; date?: string; campusDate?: string }[]
  >([]),
  maxHours = ref(4),
  loading = ref(false),
  saving = ref(false),
  error = ref(''),
  roomDialog = ref(false),
  seatDialog = ref(false),
  codeDialog = ref(false),
  dailyCode = ref(''),
  codeRoomId = ref<number>()
const blankRoom = () => ({
  id: undefined as number | undefined,
  name: '',
  building: '',
  floor: 1,
  department: '',
  openTime: '08:00',
  closeTime: '22:00',
  enabled: true,
})
const roomForm = ref(blankRoom())
const seatForm = ref({
  id: undefined as number | undefined,
  label: '',
  hasPower: false,
  enabled: true,
})
const logPager = usePagination()
const logsLoading = ref(false)
let logsGeneration = 0
async function loadLogs(target = logPager.page) {
  if (!Number.isInteger(target) || target < 1) return
  const generation = ++logsGeneration
  logsLoading.value = true
  error.value = ''
  try {
    const result = await request<Audit[]>(`/admin/audit-logs?${logPager.query(target)}`)
    if (generation === logsGeneration) {
      logs.value = result
      logPager.accept(target, result.length)
    }
  } catch (e) {
    if (generation === logsGeneration) error.value = errorMessage(e)
  } finally {
    if (generation === logsGeneration) logsLoading.value = false
  }
}
watch(tab, () => {
  if (tab.value === 'audit') {
    logPager.reset()
    void loadLogs(1)
  }
})
async function load() {
  loading.value = true
  error.value = ''
  try {
    const [r, s, c] = await Promise.all([
      request<Classroom[]>('/admin/classrooms'),
      request<{ maxReservationHours: number }>('/admin/settings'),
      request<typeof codes.value>('/admin/check-in-codes'),
    ])
    rooms.value = r
    maxHours.value = s.maxReservationHours
    codes.value = c
    roomId.value = roomId.value ?? r[0]?.id
    codeRoomId.value = codeRoomId.value ?? r[0]?.id
    await Promise.all([loadLogs(), roomId.value ? loadSeats() : Promise.resolve()])
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}
async function loadSeats() {
  if (!roomId.value) return
  error.value = ''
  try {
    seats.value = await request<Seat[]>(`/admin/classrooms/${roomId.value}/seats`)
  } catch (e) {
    error.value = errorMessage(e)
  }
}
function editRoom(room?: Classroom) {
  roomForm.value = room
    ? {
        ...room,
        department: room.department ?? '',
        openTime: room.openTime.slice(0, 5),
        closeTime: room.closeTime.slice(0, 5),
      }
    : blankRoom()
  roomDialog.value = true
}
async function saveRoom() {
  const form = roomForm.value
  if (
    !form.name.trim() ||
    !form.building.trim() ||
    !form.openTime ||
    !form.closeTime ||
    form.openTime >= form.closeTime
  ) {
    ElMessage.warning('填写教室名称、楼栋和有效开放时间')
    return
  }
  saving.value = true
  try {
    const { id, ...body } = form
    await write(
      id ? `/admin/classrooms/${id}` : '/admin/classrooms',
      {
        ...body,
        name: body.name.trim(),
        building: body.building.trim(),
        department: body.department || null,
      },
      id ? 'PUT' : 'POST',
    )
    roomDialog.value = false
    ElMessage.success('教室已保存')
    await load()
  } catch (e) {
    ElMessage.error(errorMessage(e))
  } finally {
    saving.value = false
  }
}
function editSeat(seat?: Seat) {
  seatForm.value = seat
    ? { id: seat.id, label: seat.label, hasPower: seat.hasPower, enabled: seat.enabled ?? true }
    : { id: undefined, label: '', hasPower: false, enabled: true }
  seatDialog.value = true
}
async function saveSeat() {
  if (!seatForm.value.label.trim() || !roomId.value) {
    ElMessage.warning('填写座位编号并选择教室')
    return
  }
  saving.value = true
  try {
    const { id, ...body } = seatForm.value
    await write(
      id ? `/admin/seats/${id}` : `/admin/classrooms/${roomId.value}/seats`,
      { ...body, label: body.label.trim() },
      id ? 'PUT' : 'POST',
    )
    seatDialog.value = false
    ElMessage.success('座位已保存')
    await load()
  } catch (e) {
    ElMessage.error(errorMessage(e))
  } finally {
    saving.value = false
  }
}
async function saveSettings() {
  saving.value = true
  try {
    await write('/admin/settings', { maxReservationHours: maxHours.value }, 'PUT')
    ElMessage.success('预约参数已保存')
  } catch (e) {
    ElMessage.error(errorMessage(e))
  } finally {
    saving.value = false
  }
}
async function saveCode() {
  if (!codeRoomId.value || !/^\d{6}$/.test(dailyCode.value)) {
    ElMessage.warning('请选择教室并填写6位数字签到码')
    return
  }
  saving.value = true
  try {
    await write(`/admin/check-in-codes/${codeRoomId.value}`, { code: dailyCode.value }, 'PUT')
    dailyCode.value = ''
    codeDialog.value = false
    ElMessage.success('当天签到码已设置')
    await load()
  } catch (e) {
    ElMessage.error(errorMessage(e))
  } finally {
    saving.value = false
  }
}
onMounted(load)
</script>
<template>
  <PageHeading
    eyebrow="SPACE OPERATIONS"
    title="照顾好每一处学习空间。"
    description="维护教室、座位和开放规则，让校园资源有序运行。"
    ><el-button @click="load()" :loading="loading">刷新数据</el-button></PageHeading
  ><el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
  <section class="surface" v-loading="loading">
    <el-tabs v-model="tab"
      ><el-tab-pane label="教室与座位" name="spaces" /><el-tab-pane
        label="预约参数"
        name="settings" /><el-tab-pane label="每日签到码" name="codes" /><el-tab-pane
        label="操作日志"
        name="audit" /></el-tabs
    ><template v-if="tab === 'spaces'"
      ><div class="table-toolbar">
        <h3>教室目录</h3>
        <el-button type="primary" @click="editRoom()">＋ 新增教室</el-button>
      </div>
      <el-table :data="rooms"
        ><el-table-column prop="name" label="教室" min-width="130" /><el-table-column
          label="位置"
          min-width="140"
          ><template #default="{ row }"
            >{{ row.building }} · {{ row.floor }}层</template
          ></el-table-column
        ><el-table-column label="访问院系" min-width="130"
          ><template #default="{ row }">{{
            row.department === 'CS'
              ? '计算机学院'
              : row.department === 'ART'
                ? '艺术学院'
                : row.department || '全校开放'
          }}</template></el-table-column
        ><el-table-column label="开放时间" min-width="140"
          ><template #default="{ row }"
            >{{ row.openTime.slice(0, 5) }}–{{ row.closeTime.slice(0, 5) }}</template
          ></el-table-column
        ><el-table-column prop="seatCount" label="座位" width="70" /><el-table-column
          label="状态"
          width="90"
          ><template #default="{ row }"
            ><el-tag :type="row.enabled ? 'success' : 'info'">{{
              row.enabled ? '开放' : '停用'
            }}</el-tag></template
          ></el-table-column
        ><el-table-column label="操作" width="90"
          ><template #default="{ row }"
            ><el-button link type="primary" @click="editRoom(row)">编辑</el-button></template
          ></el-table-column
        ></el-table
      >
      <div class="table-toolbar seat-admin-toolbar">
        <div class="inline-fields">
          <h3>座位维护</h3>
          <el-select v-model="roomId" @change="loadSeats" style="width: 220px"
            ><el-option v-for="r in rooms" :key="r.id" :value="r.id" :label="r.name"
          /></el-select>
        </div>
        <el-button @click="editSeat()" :disabled="!roomId">＋ 新增座位</el-button>
      </div>
      <el-table :data="seats" empty-text="该教室暂无座位"
        ><el-table-column prop="label" label="座位编号" /><el-table-column label="插座"
          ><template #default="{ row }">{{
            row.hasPower ? '有插座' : '无插座'
          }}</template></el-table-column
        ><el-table-column label="启用状态"
          ><template #default="{ row }">{{
            row.enabled === false ? '停用' : '启用'
          }}</template></el-table-column
        ><el-table-column label="操作" width="90"
          ><template #default="{ row }"
            ><el-button link type="primary" @click="editSeat(row)">编辑</el-button></template
          ></el-table-column
        ></el-table
      ></template
    ><template v-else-if="tab === 'settings'"
      ><div class="settings-panel">
        <h3>单次预约时长上限</h3>
        <p class="muted">按整点小时预约。参数变更由服务端校验，避免使现有预约失效。</p>
        <el-form label-position="top"
          ><el-form-item label="最长可预约小时数"
            ><el-input-number v-model="maxHours" :min="1" :max="12" /></el-form-item
          ><el-button type="primary" :loading="saving" @click="saveSettings"
            >保存参数</el-button
          ></el-form
        >
      </div></template
    ><template v-else-if="tab === 'codes'"
      ><div class="table-toolbar">
        <div>
          <h3>当天教室签到码</h3>
          <p class="muted">{{ campusDate() }} · 签到码只保存摘要，设置后不会显示明文。</p>
        </div>
        <el-button type="primary" @click="codeDialog = true">设置签到码</el-button>
      </div>
      <el-table :data="codes" empty-text="当天尚未设置签到码"
        ><el-table-column label="教室"
          ><template #default="{ row }">{{
            row.classroomName ||
            rooms.find((r) => r.id === row.classroomId)?.name ||
            row.classroomId
          }}</template></el-table-column
        ><el-table-column label="日期"
          ><template #default="{ row }">{{
            row.date || row.campusDate || campusDate()
          }}</template></el-table-column
        ><el-table-column label="状态"
          ><template #default><el-tag type="success">已设置</el-tag></template></el-table-column
        ></el-table
      ></template
    ><template v-else
      ><p class="muted">关键操作记录用于追踪与核对；请求编号可帮助定位问题。</p>
      <el-table :data="logs" empty-text="暂无操作日志"
        ><el-table-column label="操作" min-width="170"
          ><template #default="{ row }">{{
            actionLabel[row.action] ?? row.action
          }}</template></el-table-column
        ><el-table-column label="操作者" width="120"
          ><template #default="{ row }">{{
            row.actorName || '系统任务'
          }}</template></el-table-column
        ><el-table-column label="记录时间" min-width="160"
          ><template #default="{ row }">{{ dateTime(row.createdAt) }}</template></el-table-column
        ><el-table-column
          prop="requestId"
          label="请求编号"
          min-width="240"
          show-overflow-tooltip /></el-table
    ></template>
    <PagePager
      v-if="tab === 'audit'"
      :page="logPager.page"
      :page-size="logPager.pageSize"
      :row-count="logPager.rowCount"
      :loading="logsLoading || loading"
      @change="(page) => loadLogs(page)"
    />
  </section>
  <el-dialog v-model="roomDialog" :title="roomForm.id ? '编辑教室' : '新增教室'" width="530px"
    ><el-form label-position="top"
      ><div class="form-two-columns">
        <el-form-item label="教室名称"
          ><el-input v-model="roomForm.name" maxlength="100" /></el-form-item
        ><el-form-item label="楼栋"
          ><el-input v-model="roomForm.building" maxlength="100" /></el-form-item
        ><el-form-item label="楼层"
          ><el-input-number v-model="roomForm.floor" :min="-5" :max="100" /></el-form-item
        ><el-form-item label="院系访问规则"
          ><el-select v-model="roomForm.department"
            ><el-option label="全校开放" value="" /><el-option
              label="计算机学院"
              value="CS" /><el-option label="艺术学院" value="ART" /></el-select></el-form-item
        ><el-form-item label="开放时间"
          ><el-time-select
            v-model="roomForm.openTime"
            start="00:00"
            step="01:00"
            end="23:00" /></el-form-item
        ><el-form-item label="关闭时间"
          ><el-time-select v-model="roomForm.closeTime" start="01:00" step="01:00" end="23:00"
        /></el-form-item>
      </div>
      <el-form-item label="教室状态"
        ><el-switch
          v-model="roomForm.enabled"
          active-text="开放"
          inactive-text="停用" /></el-form-item></el-form
    ><template #footer
      ><el-button @click="roomDialog = false" :disabled="saving">取消</el-button
      ><el-button type="primary" :loading="saving" @click="saveRoom">保存教室</el-button></template
    ></el-dialog
  ><el-dialog v-model="seatDialog" :title="seatForm.id ? '编辑座位' : '新增座位'" width="420px"
    ><el-form label-position="top"
      ><el-form-item label="座位编号"
        ><el-input v-model="seatForm.label" maxlength="30" placeholder="例如 A01" /></el-form-item
      ><el-form-item label="设施"
        ><el-switch
          v-model="seatForm.hasPower"
          active-text="有插座"
          inactive-text="无插座" /></el-form-item
      ><el-form-item label="启用状态"
        ><el-switch
          v-model="seatForm.enabled"
          active-text="启用"
          inactive-text="停用" /></el-form-item></el-form
    ><template #footer
      ><el-button @click="seatDialog = false" :disabled="saving">取消</el-button
      ><el-button type="primary" :loading="saving" @click="saveSeat">保存座位</el-button></template
    ></el-dialog
  ><el-dialog v-model="codeDialog" title="设置当天签到码" width="450px"
    ><el-form label-position="top"
      ><el-form-item label="教室"
        ><el-select v-model="codeRoomId"
          ><el-option
            v-for="r in rooms"
            :key="r.id"
            :value="r.id"
            :label="r.name" /></el-select></el-form-item
      ><el-form-item label="6位数字签到码"
        ><el-input v-model="dailyCode" maxlength="6" placeholder="例如 246810" /></el-form-item
    ></el-form>
    <p class="muted">覆盖当天已有签到码后，学生需使用新码签到。</p>
    <template #footer
      ><el-button @click="codeDialog = false" :disabled="saving">取消</el-button
      ><el-button type="primary" :loading="saving" @click="saveCode"
        >保存签到码</el-button
      ></template
    ></el-dialog
  >
</template>
