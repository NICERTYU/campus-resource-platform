import { createRouter, createWebHistory } from 'vue-router'
import { useAuth } from './stores/auth'
const Login = () => import('./pages/Login.vue')
const Seats = () => import('./pages/Seats.vue')
const Bookings = () => import('./pages/Bookings.vue')
const Applications = () => import('./pages/Applications.vue')
const Repairs = () => import('./pages/Repairs.vue')
const Admin = () => import('./pages/Admin.vue')
const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: Login },
    { path: '/', redirect: '/seats' },
    { path: '/seats', component: Seats, meta: { title: '寻找自习座位' } },
    { path: '/bookings', component: Bookings, meta: { title: '我的预约' } },
    { path: '/applications', component: Applications, meta: { title: '教室申请' } },
    { path: '/repairs', component: Repairs, meta: { title: '报修服务' } },
    { path: '/admin', component: Admin, meta: { title: '空间与运营', admin: true } },
    { path: '/:pathMatch(.*)*', redirect: '/seats' },
  ],
})
router.beforeEach(async (to) => {
  const auth = useAuth()
  try {
    await auth.initialize()
  } catch {
    return to.path === '/login' ? true : '/login'
  }
  if (!auth.user && to.path !== '/login') return '/login'
  if (auth.user && to.path === '/login') return '/seats'
  if (to.meta.admin && !auth.isAdmin) return '/seats'
  if (to.path === '/applications' && !auth.isAdmin && !auth.has('APPLICANT')) return '/seats'
  return true
})
export default router
