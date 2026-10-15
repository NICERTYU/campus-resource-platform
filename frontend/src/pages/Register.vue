<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { errorMessage, refreshCsrf, write } from '../lib/api'
import { displayNameError, passwordError, usernameError } from '../lib/accounts'
import type { User } from '../lib/types'

const router = useRouter()
const username = ref('')
const displayName = ref('')
const password = ref('')
const confirmation = ref('')
const department = ref('')
const loading = ref(false)
const error = ref('')
async function register() {
  if (loading.value) return
  error.value =
    usernameError(username.value.trim()) ||
    displayNameError(displayName.value) ||
    passwordError(password.value) ||
    (password.value !== confirmation.value ? '两次输入的密码不一致' : '') ||
    (!['CS', 'ART'].includes(department.value) ? '请选择所属院系' : '')
  if (error.value) return
  loading.value = true
  try {
    await refreshCsrf()
    const user = await write<User>('/auth/register', {
      username: username.value.trim(),
      displayName: displayName.value.trim(),
      password: password.value,
      department: department.value,
    })
    password.value = ''
    confirmation.value = ''
    await router.push({ path: '/login', query: { registered: '1', username: user.username } })
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}
</script>
<template>
  <div class="login-page">
    <section class="login-story">
      <router-link to="/login" class="brand">
        <span class="brand-mark">栖</span>
        <div>栖学<span>CAMPUS SPACES</span></div>
      </router-link>
      <div class="story-copy">
        <p class="eyebrow">MAKE ROOM FOR LEARNING</p>
        <h1>新的学习计划，<br />从这里开始。</h1>
        <p>加入校园空间服务。<br />寻找座位、安排学习，也一起照顾校园。</p>
      </div>
      <small>栖学 · 校园资源服务平台</small>
    </section>
    <section class="login-form-panel">
      <div class="login-form registration-form">
        <p class="eyebrow">JOIN CAMPUS SPACES</p>
        <h2>创建学生账号</h2>
        <p class="muted">注册后可预约座位和提交报修。其他角色由管理员分配。</p>
        <el-alert
          v-if="error"
          :title="error"
          type="error"
          :closable="false"
          show-icon
          class="form-error"
        />
        <el-form label-position="top" @submit.prevent="register">
          <el-form-item label="校园账号">
            <el-input
              v-model="username"
              aria-label="校园账号"
              autocomplete="username"
              placeholder="3–32位字母、数字或下划线"
              maxlength="32"
              size="large"
            />
          </el-form-item>
          <el-form-item label="姓名">
            <el-input
              v-model="displayName"
              aria-label="姓名"
              autocomplete="name"
              placeholder="请输入姓名"
              maxlength="120"
              size="large"
            />
          </el-form-item>
          <el-form-item label="所属院系">
            <select v-model="department" aria-label="所属院系" class="native-select">
              <option disabled value="">请选择所属院系</option>
              <option value="CS">计算机学院</option>
              <option value="ART">艺术学院</option>
            </select>
          </el-form-item>
          <el-form-item label="登录密码">
            <el-input
              v-model="password"
              aria-label="登录密码"
              type="password"
              autocomplete="new-password"
              placeholder="至少8个字符"
              show-password
              size="large"
            />
          </el-form-item>
          <el-form-item label="确认密码">
            <el-input
              v-model="confirmation"
              aria-label="确认密码"
              type="password"
              autocomplete="new-password"
              placeholder="请再次输入密码"
              show-password
              size="large"
            />
          </el-form-item>
          <el-button
            type="primary"
            native-type="submit"
            class="login-submit"
            size="large"
            :loading="loading"
            >注册学生账号</el-button
          >
        </el-form>
        <p class="auth-switch">已有账号？<router-link to="/login">返回登录</router-link></p>
      </div>
    </section>
  </div>
</template>
