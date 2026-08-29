import axios from 'axios'
import { useToastStore } from '@/stores/toast'
import { useAuthStore } from '@/stores/auth'
import router from '@/router'

// ============================================================
// axios 实例 + 拦截器（真后端模式）
// - 请求：Authorization: Bearer <token>
// - 响应：解包信封 {code,message,data}；code!==0 抛 {code,message}
// - 40101 → 静默刷新重放一次；40100 → 清本地跳登录
// handleEnvelope() 供业务直接对"透传信封"的场景用（通用工具）。
// ============================================================

export const http = axios.create({
  baseURL: '/api',
  timeout: 15000
})

// 诊断开关：仅开发期用，语焉不详的错误排查更高效
const DEBUG = import.meta.env.DEV && false

/** 业务层统一处理信封/code 异常：toast + 登录跳转 + 降级提示 */
export function handleEnvelope(res, { silent = false } = {}) {
  const toast = useToastStore()
  if (res && typeof res.code === 'number' && res.code > 0) {
    if (!silent) toast.error(res.message || `请求失败(${res.code})`)
    if (res.code === 40100) {
      const auth = useAuthStore()
      auth.logout()
      router.push({ name: 'login', query: { redirect: router.currentRoute.value.fullPath } })
    }
    if (res.code === 50100 || res.code === 50200 || res.code === 50300) toast.degrade(res.message || 'AI 服务暂不可用：已降级为纯统计结果')
    throw res
  }
  return res && typeof res === 'object' && 'data' in res ? res.data : res
}

// ---- 请求拦截：挂 token ----
http.interceptors.request.use((config) => {
  const auth = useAuthStore()
  if (auth.token) config.headers.Authorization = `Bearer ${auth.token}`
  return config
})

// ---- 响应拦截：解包信封 ----
http.interceptors.response.use(
  (response) => {
    const body = response.data
    if (body && typeof body.code === 'number') {
      if (body.code === 0) return body.data
      const errInfo = { code: body.code, message: body.message, response }
      if (DEBUG) console.warn('[http] 业务错误', body)
      // 40101 → 交给错误分支做刷新重放
      return Promise.reject(errInfo)
    }
    return body
  },
  async (error) => {
    if (DEBUG && !error.code) console.warn('[http] 网络错误', error.message)
    // 网络/超时
    if (!error.response) {
      const toast = useToastStore()
      if (error.code === 'ECONNABORTED') {
        toast.error('请求超时，请检查后端服务或稍后重试')
      } else {
        toast.error('网络异常：服务暂时不可达')
      }
      return Promise.reject({ code: error.code || 'NETWORK', message: error.message })
    }
    const status = error.response.status
    const body = error.response.data || {}
    const code = body.code || status * 100
    // 40101：一次静默刷新重放
    if (code === 40101) {
      const auth = useAuthStore()
      const origConfig = error.config
      if (!origConfig._retried) {
        origConfig._retried = true
        try {
          const refreshed = await auth.refresh()
          if (refreshed) {
            origConfig.headers.Authorization = `Bearer ${auth.token}`
            return http(origConfig)
          }
        } catch (e) {
          /* refresh 失败 → 落 40100 分支 */
        }
        const toast = useToastStore()
        toast.error('登录已过期，请重新登录')
        auth.logout()
        router.push({ name: 'login', query: { redirect: router.currentRoute.value.fullPath } })
      }
      return Promise.reject({ code, message: body.message || '登录过期' })
    }
    // 40100：登出跳登录
    if (code === 40100) {
      const auth = useAuthStore()
      auth.logout()
      router.push({ name: 'login', query: { redirect: router.currentRoute.value.fullPath } })
    }
    // 50100 / 50200 / 50300：旁车/LLM 降级提示
    if (code === 50100 || code === 50200 || code === 50300) {
      const toast = useToastStore()
      toast.degrade(body.message || 'AI 服务暂不可用：已降级为纯统计结果')
    } else {
      const toast = useToastStore()
      if (!silentFor(code)) toast.error(`请求失败(${code})：${body.message || error.message || '未知错误'}`)
    }
    return Promise.reject({ code, message: body.message || error.message })
  }
)

// 部分错误码不 toast 全局（由调用方自行给出更友好的文案）
function silentFor(code) {
  return code === 40000 // 登录失败由 LoginView 自渲染
}

export default http
