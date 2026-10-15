<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuth } from '../stores/auth'
import { errorMessage } from '../lib/api'
const auth = useAuth(),
  router = useRouter(),
  username = ref(''),
  password = ref(''),
  loading = ref(false),
  error = ref('')
const showDemoAccounts = import.meta.env.DEV
const accounts = [
  { name: 'student', label: '学生' },
  { name: 'student2', label: '学生二' },
  { name: 'outsider', label: '艺术学院学生' },
  { name: 'applicant', label: '申请人' },
  { name: 'admin', label: '管理员' },
  { name: 'technician', label: '维修人员' },
]
function choose(name: string) {
  if (!showDemoAccounts) return
  username.value = name
  password.value = 'Campus123!'
  error.value = ''
}
async function login() {
  error.value = ''
  if (!username.value.trim() || !password.value) {
    error.value = '请填写账号和密码'
    return
  }
  loading.value = true
  try {
    await auth.login(username.value.trim(), password.value)
    await router.push('/seats')
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
      <a class="brand"
        ><span class="brand-mark">栖</span>
        <div>栖学<span>CAMPUS SPACES</span></div></a
      >
      <div class="story-copy">
        <p class="eyebrow">A SPACE TO FOCUS</p>
        <h1>给专注，<br />留一个好位置。</h1>
        <p>发现校园里的安静角落。<br />座位预约、教室申请与报修服务，一站连接。</p>
        <div class="space-illustration" aria-hidden="true">
          <div class="illus-window"><span></span><span></span><span></span></div>
          <div class="illus-desk"><i></i><i></i></div>
          <div class="illus-chair"></div>
          <div class="illus-plant">✳</div>
          <span class="illus-caption">让每一份热爱，有处安放。</span>
        </div>
      </div>
      <small>栖学 · 校园资源服务平台</small>
    </section>
    <section class="login-form-panel">
      <div class="login-form">
        <p class="eyebrow">WELCOME TO CAMPUS</p>
        <h2>欢迎回来</h2>
        <p class="muted">登录校园账号，开启今天的学习计划。</p>
        <el-alert
          v-if="error"
          :title="error"
          type="error"
          :closable="false"
          show-icon
          class="form-error"
        /><el-form @submit.prevent="login" label-position="top"
          ><el-form-item label="校园账号"
            ><el-input
              v-model="username"
              autocomplete="username"
              placeholder="请输入账号"
              size="large" /></el-form-item
          ><el-form-item label="登录密码"
            ><el-input
              v-model="password"
              type="password"
              autocomplete="current-password"
              placeholder="请输入密码"
              show-password
              size="large" /></el-form-item
          ><el-button
            type="primary"
            native-type="submit"
            size="large"
            :loading="loading"
            class="login-submit"
            >登录平台 <span>↗</span></el-button
          ></el-form
        >
        <div v-if="showDemoAccounts" class="demo-accounts">
          <strong>演示账号</strong>
          <p>选择角色即可填入预置账号。密码为 Campus123!</p>
          <div class="account-chips">
            <button v-for="account in accounts" :key="account.name" @click="choose(account.name)">
              {{ account.label }}
            </button>
          </div>
          <p class="account-note">
            计算机学院：student、student2、applicant<br />艺术学院：outsider；运营：admin；维修：technician
          </p>
        </div>
      </div>
    </section>
  </div>
</template>
