// ============================================================
// API 层总入口：直连真后端。
//   axios → /api → 主后端(8081)，拦截器在 ./http 解包信封。
// 页面一律 import { xxxApi } from '@/api'，不直接碰 axios / 信封。
// ============================================================
import http from './http'

/** 统一请求入口：走 axios 拦截器解包信封 */
export async function request(config) {
  return http(config)
}

export { http }
export * from './endpoints'
