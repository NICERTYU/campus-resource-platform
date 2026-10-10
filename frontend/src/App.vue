<script setup lang="ts">
import { computed, ref, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuth } from './stores/auth'
import { roleLabel } from './lib/status'
import { errorMessage } from './lib/api'
import { ElMessage } from 'element-plus'
const auth = useAuth(),
  route = useRoute(),
  router = useRouter(),
  menuOpen = ref(false)
const userRoles = computed(() => auth.user?.roles.map((role) => roleLabel[role]).join(' · '))
const navigation = computed(() => [
  { path: '/seats', label: '寻找座位', icon: '▦', detail: '安静学习，从这里开始' },
  { path: '/bookings', label: '我的预约', icon: '◷', detail: '安排你的学习时间' },
  ...(auth.isAdmin || auth.has('APPLICANT')
    ? [{ path: '/applications', label: '教室申请', icon: '▤', detail: '活动与整室使用' }]
    : []),
  { path: '/repairs', label: '报修服务', icon: '⌁', detail: '让空间保持良好状态' },
  ...(auth.isAdmin
    ? [{ path: '/admin', label: '空间与运营', icon: '⚙', detail: '基础数据与操作记录' }]
    : []),
])
function sessionExpired() {
  auth.user = null
  auth.initialized = false
  menuOpen.value = false
  void router.push('/login')
}
onMounted(() => window.addEventListener('campus:session-expired', sessionExpired))
onUnmounted(() => window.removeEventListener('campus:session-expired', sessionExpired))
async function logout() {
  try {
    await auth.logout()
    await router.push('/login')
  } catch (e) {
    ElMessage.error(errorMessage(e))
  }
}
</script>
<template>
  <router-view v-if="route.path === '/login'" />
  <div v-else class="app-shell">
    <aside class="sidebar" :class="{ open: menuOpen }">
      <router-link to="/seats" class="brand"
        ><span class="brand-mark">栖</span>
        <div>栖学<span>CAMPUS SPACES</span></div></router-link
      >
      <p class="nav-caption">校园资源服务</p>
      <nav>
        <router-link
          v-for="item in navigation"
          :key="item.path"
          :to="item.path"
          @click="menuOpen = false"
          ><span class="nav-icon">{{ item.icon }}</span>
          <div>
            {{ item.label }}<small>{{ item.detail }}</small>
          </div></router-link
        >
      </nav>
      <div class="sidebar-note">
        <span class="note-line"></span><strong>好好学习，也好好生活。</strong>
        <p>每一处空间，都是新的可能。</p>
      </div>
      <div class="user-profile">
        <span class="avatar">{{ auth.user?.displayName?.slice(0, 1) }}</span>
        <div>
          <strong>{{ auth.user?.displayName }}</strong
          ><small>{{ userRoles }}</small>
        </div>
        <button @click="logout" title="退出登录" aria-label="退出登录">↗</button>
      </div>
    </aside>
    <div class="main-shell">
      <header class="topbar">
        <button class="mobile-menu" @click="menuOpen = !menuOpen" aria-label="展开菜单">☰</button
        ><span>校园 <span class="breadcrumb-slash">/</span> {{ route.meta.title }}</span
        ><span class="campus-context"
          ><span class="live-dot"></span
          >{{
            auth.user?.department === 'CS'
              ? '计算机学院'
              : auth.user?.department === 'ART'
                ? '艺术学院'
                : auth.user?.department || '校园服务'
          }}
          · 校园时区</span
        >
      </header>
      <main class="content"><router-view /></main>
      <footer class="page-footer">
        栖学 · 校园资源服务平台 <span>时间均为北京时间（UTC+8）</span>
      </footer>
    </div>
    <div v-if="menuOpen" class="mobile-overlay" @click="menuOpen = false"></div>
  </div>
</template>
