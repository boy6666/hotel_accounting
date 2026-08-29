import { request } from './index'
import http from './http'

// ============================================================
// 业务端点统一出口（docs/03 全部 §2–§13）
// 页面只依赖这些函数签名，不碰 axios/信封 —— 换真实后端零改动。
// 说明：request() 走 axios 拦截器，统一返回已解包的 data。
// ============================================================

// ---- §2 认证 ----
export const authApi = {
  login(params) { return request({ method: 'POST', url: '/auth/login', data: params, params }) },
  refresh(params) { return request({ method: 'POST', url: '/auth/refresh', data: params, params }) },
  changePassword(data) { return request({ method: 'POST', url: '/auth/change-password', data }) }
}

// ---- §5 首页看板 ----
export const dashboardApi = {
  overview(month) { return request({ url: '/dashboard/overview', params: { month } }) },
  trend(from, to) { return request({ url: '/dashboard/trend', params: { from, to } }) },
  costStructure(month) { return request({ url: '/dashboard/cost-structure', params: { month } }) },
  channelRatio(month) { return request({ url: '/dashboard/channel-ratio', params: { month } }) },
  reconcile(month) { return request({ url: '/dashboard/reconcile', params: { month } }) }
}

// ---- §6 成本 ----
export const costApi = {
  list(month, extra = {}) { return request({ url: '/costs', params: { month, ...extra } }) },
  create(data) { return request({ method: 'POST', url: '/costs', data }) },
  update(id, data) { return request({ method: 'PUT', url: `/costs/${id}`, data }) },
  remove(id) { return request({ method: 'DELETE', url: `/costs/${id}` }) },
  summary(month) { return request({ url: '/costs/summary', params: { month } }) },
  trend(from, to) { return request({ url: '/costs/trend', params: { from, to } }) }
}

// ---- §7 渠道 ----
export const channelApi = {
  list(params = {}) { return request({ url: '/channels', params }) },
  create(data) { return request({ method: 'POST', url: '/channels', data }) },
  update(id, data) { return request({ method: 'PUT', url: `/channels/${id}`, data }) },
  monthly(month) { return request({ url: '/channel-monthly', params: { month } }) },
  trend(from, to) { return request({ url: '/channel-monthly/trend', params: { from, to } }) }
}

// ---- §8 利润 ----
export const profitApi = {
  monthly(from, to) { return request({ url: '/profit/monthly', params: { from, to } }) },
  summary(month) { return request({ url: '/profit/summary', params: { month } }) }
}

// ---- §9 房态 ----
export const occApi = {
  matrix(month) { return request({ url: '/occupancy/matrix', params: { month } }) },
  dayRooms(bizDate) { return request({ url: '/occupancy/day-rooms', params: { bizDate } }) },
  updateDayRooms(bizDate, roomNos, note) { return request({ method: 'PUT', url: '/occupancy/day-rooms', data: { bizDate, roomNos, note } }) },
  batch(rows) { return request({ method: 'POST', url: '/occupancy/batch', data: { rows } }) },
  daily(month) { return request({ url: '/occupancy/daily', params: { month } }) },
  reconcile(month) { return request({ url: '/occupancy/reconcile', params: { month } }) },
  workdayRate(month) { return request({ url: '/occupancy/workday-rate', params: { month } }) }
}

// ---- §10 定价 · 预测 ----
export const pricingApi = {
  // 档位（§10.1–10.4）
  tiers() { return request({ url: '/pricing/tiers' }) },
  createTier(data) { return request({ method: 'POST', url: '/pricing/tiers', data }) },
  updateTier(id, data) { return request({ method: 'PUT', url: `/pricing/tiers/${id}`, data }) },
  deleteTier(id) { return request({ method: 'DELETE', url: `/pricing/tiers/${id}` }) },
  // 临近日建议价（§10.5–10.7）
  suggestions(from, to) { return request({ url: '/pricing/suggestions', params: { from, to } }) },
  generateSuggestions(from, to) { return request({ method: 'POST', url: '/pricing/suggestions/generate', data: { from, to } }) },
  updateSuggestion(bizDate, suggestedPrice) { return request({ method: 'PUT', url: `/pricing/suggestions/${bizDate}`, data: { suggestedPrice } }) },
  // 目标倒推（§10.8–10.10）
  calcTarget(params) { return request({ url: '/pricing/calc/target', params }) },
  saveCalcScenario(data) { return request({ method: 'POST', url: '/pricing/calc/scenarios', data }) },
  listCalcScenarios() { return request({ url: '/pricing/calc/scenarios' }) }
}

// ---- §10 预测 · LLM（§10.11–10.13）----
export const predictionApi = {
  generate(month, metric) { return request({ method: 'POST', url: '/prediction/generate', data: { month, metric } }) },
  results(target, metric) { return request({ url: '/prediction/results', params: { target, metric } }) },
  daily(params) { return request({ url: '/prediction/daily', params }) }
}

// ---- §11 回本测算 ----
export const breakevenApi = {
  scenarios() { return request({ url: '/breakeven/scenarios' }) },
  createScenario(data) { return request({ method: 'POST', url: '/breakeven/scenarios', data }) },
  updateScenario(id, data) { return request({ method: 'PUT', url: `/breakeven/scenarios/${id}`, data }) },
  deleteScenario(id) { return request({ method: 'DELETE', url: `/breakeven/scenarios/${id}` }) },
  cashflow(id) { return request({ url: `/breakeven/scenarios/${id}/cashflow` }) },
  sensitivity(id) { return request({ url: `/breakeven/scenarios/${id}/sensitivity` }) }
}

// ---- §13 房间 / 设置 ----
export const roomApi = {
  list(params = {}) { return request({ url: '/rooms', params }) },
  create(data) { return request({ method: 'POST', url: '/rooms', data }) },
  update(id, data) { return request({ method: 'PUT', url: `/rooms/${id}`, data }) },
  disable(id) { return request({ method: 'POST', url: `/rooms/${id}/disable` }) }
}

export const settingsApi = {
  hotel() { return request({ url: '/settings/hotel' }) },
  updateHotel(data) { return request({ method: 'PUT', url: '/settings/hotel', data }) }
}

// ---- §12 导入 ----
export const importApi = {
  /** 经主后端返回真实模板 Excel（responseType=blob） */
  template() {
    return request({ url: '/imports/template', responseType: 'blob' })
  },
  list(month, params = {}) { return request({ url: '/imports', params: { month, ...params } }) },
  upload({ month, file }) {
    // multipart 上传真实 Excel（file 字段名对齐 ImportController.upload，log 参数结构 {month, file}）
    const form = new FormData()
    form.append('month', month)
    if (file) form.append('file', file)
    return http.post('/imports', form)
  },
  detail(id) { return request({ url: `/imports/${id}` }) },
  preview(id) { return request({ url: `/imports/${id}/preview` }) },
  mapping(id) { return request({ url: `/imports/${id}/mapping` }) },
  confirm(id, data) { return request({ method: 'POST', url: `/imports/${id}/confirm`, data }) },
  remove(id) { return request({ method: 'DELETE', url: `/imports/${id}` }) }
}
