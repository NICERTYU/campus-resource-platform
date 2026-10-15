import { createRouter, createWebHistory } from 'vue-router'
import { useAuth } from './stores/auth'
const Login = () => import('./pages/Login.vue')
const Register = () => import('./pages/Register.vue')
const Accounts = () => import('./pages/Accounts.vue')
const Seats = () => import('./pages/Seats.vue')
const Bookings = () => import('./pages/Bookings.vue')
const Applications = () => import('./pages/Applications.vue')
const Repairs = () => import('./pages/Repairs.vue')
const Admin = () => import('./pages/Admin.vue')
const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: Login },
    { path: '/register', component: Register },
    { path: '/', redirect: '/seats' },
    { path: '/seats', component: Seats, meta: { title: '寻找自习座位' } },
    { path: '/bookings', component: Bookings, meta: { title: '我的预约' } },
    { path: '/applications', component: Applications, meta: { title: '教室申请' } },
    { path: '/repairs', component: Repairs, meta: { title: '报修服务' } },
    { path: '/admin', component: Admin, meta: { title: '空间与运营', admin: true } },
    { path: '/admin/accounts', component: Accounts, meta: { title: '账号管理', admin: true } },
    { path: '/:pathMatch(.*)*', redirect: '/seats' },
  ],
})
router.beforeEach(async (to) => {
  const auth = useAuth()
  const publicPage = ['/login', '/register'].includes(to.path)
  try {
    await auth.initialize()
  } catch {
    return publicPage ? true : '/login'
  }
  if (!auth.user && !publicPage) return '/login'
  if (auth.user && publicPage) return '/seats'
  if (to.meta.admin && !auth.isAdmin) return '/seats'
  if (to.path === '/applications' && !auth.isAdmin && !auth.has('APPLICANT')) return '/seats'
  return true
})
export default router
