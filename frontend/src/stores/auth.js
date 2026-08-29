import { defineStore } from 'pinia'
import { authApi } from '@/api'

// 认证 store：JWT token + refreshToken + user（单用户）。
// token 校验交给 axios 拦截器（40100 清本地跳登录 / 40101 刷新重放）。
const TOKEN_KEY = 'ha.token'
const REFRESH_KEY = 'ha.refresh'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem(TOKEN_KEY) || '',
    refreshToken: localStorage.getItem(REFRESH_KEY) || '',
    user: null
  }),
  getters: {
    isLoggedIn: (s) => !!s.token
  },
  actions: {
    async login(username, password) {
      const data = await authApi.login({ username, password })
      this.token = data.token
      this.refreshToken = data.refreshToken || ''
      this.user = data.user || { username, displayName: '管理员' }
      localStorage.setItem(TOKEN_KEY, this.token)
      if (this.refreshToken) localStorage.setItem(REFRESH_KEY, this.refreshToken)
      return data
    },
    async logout() {
      this.token = ''
      this.refreshToken = ''
      this.user = null
      localStorage.removeItem(TOKEN_KEY)
      localStorage.removeItem(REFRESH_KEY)
    },
    /** 用刷新令牌换新 JWT（40101 时由拦截器调用） */
    async refresh() {
      if (!this.refreshToken) throw new Error('无刷新令牌')
      const data = await authApi.refresh({ refreshToken: this.refreshToken })
      this.token = data.token
      if (data.refreshToken) this.refreshToken = data.refreshToken
      localStorage.setItem(TOKEN_KEY, this.token)
      if (data.refreshToken) localStorage.setItem(REFRESH_KEY, data.refreshToken)
      return data
    },
    /** 半自动登录：已有本地 token 直接进；缺失 → 跳登录页（供路由守卫调用） */
    ensure() {
      return this.isLoggedIn
    }
  }
})
