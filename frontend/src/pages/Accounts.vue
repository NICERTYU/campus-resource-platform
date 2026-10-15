<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import PageHeading from '../components/PageHeading.vue'
import PagePager from '../components/PagePager.vue'
import { request, write, errorMessage } from '../lib/api'
import { accountRoles, displayNameError, passwordError, usernameError } from '../lib/accounts'
import { usePagination } from '../lib/pagination'
import { roleLabel } from '../lib/status'
import { useAuth } from '../stores/auth'
import type { Account, Role } from '../lib/types'

const router = useRouter()
const auth = useAuth()
const accounts = ref<Account[]>([])
const query = ref('')
const appliedQuery = ref('')
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const formError = ref('')
const editorOpen = ref(false)
const editing = ref<Account | null>(null)
const passwordTarget = ref<Account | null>(null)
const newPassword = ref('')
const confirmPassword = ref('')
const pager = usePagination(20)
const blankForm = () => ({
  username: '',
  displayName: '',
  department: '',
  password: '',
  roles: ['STUDENT'] as Role[],
  enabled: true,
})
const form = ref(blankForm())
const roleNames = (roles: Role[]) => roles.map((role) => roleLabel[role]).join(' · ')
let loadGeneration = 0
async function load(target = pager.page) {
  if (!Number.isInteger(target) || target < 1) return
  const generation = ++loadGeneration
  loading.value = true
  error.value = ''
  try {
    const search = new URLSearchParams(pager.query(target))
    search.set('query', appliedQuery.value)
    const result = await request<Account[]>(`/admin/accounts?${search}`)
    if (generation === loadGeneration) {
      accounts.value = result
      pager.accept(target, result.length)
    }
  } catch (e) {
    if (generation === loadGeneration) error.value = errorMessage(e)
  } finally {
    if (generation === loadGeneration) loading.value = false
  }
}
function search() {
  appliedQuery.value = query.value.trim()
  void load(1)
}
function edit(account?: Account) {
  editing.value = account ?? null
  form.value = account
    ? { ...account, department: account.department ?? '', roles: [...account.roles], password: '' }
    : blankForm()
  formError.value = ''
  editorOpen.value = true
}
function openPassword(account: Account) {
  passwordTarget.value = account
  newPassword.value = ''
  confirmPassword.value = ''
  formError.value = ''
}
function closeEditor() {
  if (saving.value) return
  editorOpen.value = false
  form.value.password = ''
}
function closePassword() {
  if (saving.value) return
  passwordTarget.value = null
  newPassword.value = ''
  confirmPassword.value = ''
}
async function save() {
  if (saving.value) return
  const current = editing.value
  formError.value =
    (!current
      ? usernameError(form.value.username.trim()) || passwordError(form.value.password)
      : '') ||
    displayNameError(form.value.displayName) ||
    (form.value.department.trim().length > 80 ? '院系不能超过80个字符' : '') ||
    (!form.value.roles.length || form.value.roles.some((role) => !accountRoles.includes(role))
      ? '请至少选择一个有效角色'
      : '') ||
    (current?.id === auth.user?.id && (!form.value.enabled || !form.value.roles.includes('ADMIN'))
      ? '不能停用自己的账号或取消自己的管理员角色'
      : '')
  if (formError.value) return
  saving.value = true
  try {
    const body = {
      displayName: form.value.displayName.trim(),
      department: form.value.department.trim() || null,
      roles: [...form.value.roles],
      enabled: form.value.enabled,
    }
    const saved = await write<Account>(
      current ? `/admin/accounts/${current.id}` : '/admin/accounts',
      current
        ? body
        : { ...body, username: form.value.username.trim(), password: form.value.password },
      current ? 'PUT' : 'POST',
    )
    editorOpen.value = false
    form.value.password = ''
    ElMessage.success(current ? '账号已更新' : '账号已创建')
    if (saved.id === auth.user?.id) {
      // A name-only edit keeps the session; department changes revoke it on the server.
      if (
        current &&
        (current.department !== saved.department ||
          current.roles.join(',') !== saved.roles.join(','))
      ) {
        auth.user = null
        auth.initialized = false
        await router.push('/login')
        return
      }
      auth.user = { ...saved, department: saved.department ?? '' }
    }
    await load(current ? pager.page : 1)
  } catch (e) {
    formError.value = errorMessage(e)
  } finally {
    saving.value = false
  }
}
async function resetPassword() {
  if (saving.value || !passwordTarget.value) return
  formError.value =
    passwordError(newPassword.value) ||
    (newPassword.value !== confirmPassword.value ? '两次输入的密码不一致' : '')
  if (formError.value) return
  saving.value = true
  try {
    const target = passwordTarget.value
    await write(`/admin/accounts/${target.id}/password`, { password: newPassword.value })
    passwordTarget.value = null
    newPassword.value = ''
    confirmPassword.value = ''
    ElMessage.success('密码已重置')
    if (target.id === auth.user?.id) {
      auth.user = null
      auth.initialized = false
      await router.push({
        path: '/login',
        query: { passwordReset: '1', username: target.username },
      })
    }
  } catch (e) {
    formError.value = errorMessage(e)
  } finally {
    saving.value = false
  }
}
onMounted(() => load())
</script>
<template>
  <PageHeading
    eyebrow="CAMPUS ACCOUNTS"
    title="让每个人，各得其位。"
    description="管理校园账号、角色与启用状态。学生也可以从登录页自行注册。"
  >
    <el-button @click="load()" :loading="loading">刷新账号</el-button>
    <el-button type="primary" data-testid="account-create" @click="edit()">新增账号</el-button>
  </PageHeading>
  <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
  <section class="surface" v-loading="loading">
    <form class="account-search" @submit.prevent="search">
      <el-input
        v-model="query"
        aria-label="搜索账号或姓名"
        data-testid="account-search"
        placeholder="搜索账号或姓名"
        clearable
      />
      <el-button native-type="submit" :loading="loading">搜索账号</el-button>
    </form>
    <el-table :data="accounts" empty-text="没有符合条件的账号" :row-key="(row: Account) => row.id">
      <el-table-column label="账号" min-width="150"
        ><template #default="{ row }"
          ><strong :data-testid="`account-row-${row.id}`">{{ row.username }}</strong
          ><small v-if="row.id === auth.user?.id">（自己）</small></template
        ></el-table-column
      >
      <el-table-column prop="displayName" label="姓名" min-width="110" />
      <el-table-column label="院系" min-width="120"
        ><template #default="{ row }">{{
          row.department === 'CS'
            ? '计算机学院'
            : row.department === 'ART'
              ? '艺术学院'
              : row.department || '未设置'
        }}</template></el-table-column
      >
      <el-table-column label="角色" min-width="190"
        ><template #default="{ row }">{{ roleNames(row.roles) }}</template></el-table-column
      >
      <el-table-column label="状态" width="90"
        ><template #default="{ row }"
          ><el-tag :type="row.enabled ? 'success' : 'info'">{{
            row.enabled ? '启用' : '停用'
          }}</el-tag></template
        ></el-table-column
      >
      <el-table-column label="操作" width="185"
        ><template #default="{ row }"
          ><el-button link type="primary" @click="edit(row)">编辑账号</el-button
          ><el-button link type="primary" @click="openPassword(row)">重置密码</el-button></template
        ></el-table-column
      >
    </el-table>
    <PagePager
      :page="pager.page"
      :page-size="pager.pageSize"
      :row-count="pager.rowCount"
      :loading="loading"
      @change="load"
    />
  </section>
  <el-dialog
    :model-value="editorOpen"
    :title="editing ? '编辑账号' : '新增账号'"
    width="520px"
    :close-on-click-modal="!saving"
    :close-on-press-escape="!saving"
    :show-close="!saving"
    @close="closeEditor"
  >
    <div v-if="editing" class="account-target">
      正在修改：<strong>{{ editing.displayName }}（{{ editing.username }}）</strong>
      <p class="muted">确认以下角色和状态后保存。修改院系、角色或停用账号会使该账号重新登录。</p>
    </div>
    <el-alert
      v-if="formError"
      :title="formError"
      type="error"
      :closable="false"
      show-icon
      class="form-error"
    />
    <el-form label-position="top" @submit.prevent="save">
      <el-form-item label="校园账号"
        ><el-input
          v-model="form.username"
          aria-label="校园账号"
          :disabled="!!editing || saving"
          autocomplete="off"
          maxlength="32"
          placeholder="字母开头，3–32位字母、数字或下划线"
      /></el-form-item>
      <el-form-item label="姓名"
        ><el-input v-model="form.displayName" aria-label="姓名" maxlength="120" :disabled="saving"
      /></el-form-item>
      <el-form-item label="院系"
        ><el-input
          v-model="form.department"
          aria-label="院系"
          maxlength="80"
          placeholder="CS 为计算机学院，ART 为艺术学院，可留空"
          :disabled="saving"
      /></el-form-item>
      <el-form-item v-if="!editing" label="初始密码"
        ><el-input
          v-model="form.password"
          aria-label="初始密码"
          type="password"
          autocomplete="new-password"
          show-password
          placeholder="至少8个字符"
          :disabled="saving"
      /></el-form-item>
      <el-form-item label="账号角色"
        ><el-checkbox-group v-model="form.roles" class="account-role-group" aria-label="账号角色"
          ><el-checkbox
            v-for="role in accountRoles"
            :key="role"
            :value="role"
            :disabled="saving || (editing?.id === auth.user?.id && role === 'ADMIN')"
            >{{ roleLabel[role] }}</el-checkbox
          ></el-checkbox-group
        ></el-form-item
      >
      <el-form-item label="账号状态"
        ><el-switch
          v-model="form.enabled"
          aria-label="账号启用状态"
          active-text="启用"
          inactive-text="停用"
          :disabled="saving || editing?.id === auth.user?.id"
      /></el-form-item>
      <p v-if="editing?.id === auth.user?.id" class="muted">
        不能停用自己的账号或取消自己的管理员角色。
      </p>
    </el-form>
    <template #footer
      ><el-button :disabled="saving" @click="closeEditor">取消</el-button
      ><el-button type="primary" :loading="saving" @click="save">{{
        editing ? '保存并确认' : '创建账号'
      }}</el-button></template
    >
  </el-dialog>
  <el-dialog
    :model-value="!!passwordTarget"
    title="重置密码"
    width="490px"
    :close-on-click-modal="!saving"
    :close-on-press-escape="!saving"
    :show-close="!saving"
    @close="closePassword"
  >
    <div class="account-target">
      确认重置：<strong>{{ passwordTarget?.displayName }}（{{ passwordTarget?.username }}）</strong>
      <p class="muted">保存后该账号的现有登录会失效，请将新密码告知本人。</p>
    </div>
    <el-alert
      v-if="formError"
      :title="formError"
      type="error"
      :closable="false"
      show-icon
      class="form-error"
    />
    <el-form label-position="top" @submit.prevent="resetPassword">
      <el-form-item label="新密码"
        ><el-input
          v-model="newPassword"
          aria-label="新密码"
          type="password"
          autocomplete="new-password"
          show-password
          :disabled="saving"
          placeholder="至少8个字符"
      /></el-form-item>
      <el-form-item label="确认新密码"
        ><el-input
          v-model="confirmPassword"
          aria-label="确认新密码"
          type="password"
          autocomplete="new-password"
          show-password
          :disabled="saving"
      /></el-form-item>
    </el-form>
    <template #footer
      ><el-button :disabled="saving" @click="closePassword">取消</el-button
      ><el-button type="primary" :loading="saving" @click="resetPassword"
        >确认重置密码</el-button
      ></template
    >
  </el-dialog>
</template>
