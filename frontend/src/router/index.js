import { createRouter, createWebHashHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

// hash 路由（便于本地文件/任意静态托管）；一期页 + 二期占位壳
const routes = [
  { path: '/login', name: 'login', component: () => import('@/views/LoginView.vue'), meta: { public: true } },
  { path: '/', name: 'dashboard', component: () => import('@/views/DashboardView.vue') },
  { path: '/costs', name: 'costs', component: () => import('@/views/CostView.vue') },
  { path: '/channels', name: 'channels', component: () => import('@/views/ChannelsView.vue') },
  { path: '/profit', name: 'profit', component: () => import('@/views/ProfitView.vue') },
  { path: '/occupancy', name: 'occupancy', component: () => import('@/views/OccupancyView.vue') },
  { path: '/pricing', name: 'pricing', component: () => import('@/views/PricingView.vue') },
  { path: '/breakeven', name: 'breakeven', component: () => import('@/views/BreakevenView.vue') },
  { path: '/settings', name: 'settings', component: () => import('@/views/SettingsView.vue') },
  { path: '/:pathMatch(.*)*', redirect: '/' }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

// 守卫：未登录跳登录；已登录访问 login 回首页
router.beforeEach((to) => {
  const auth = useAuthStore()
  if (!to.meta.public && !auth.isLoggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.name === 'login' && auth.isLoggedIn) return { name: 'dashboard' }
})

export default router
