// 展示层格式化工具（契约：金额 JSON 为字符串/数值，前端千分位格式化归展示层）

/** 千分位金额（保留 2 位小数） */
export function fmtMoney(v, opts = {}) {
  const n = Number(v)
  if (!isFinite(n)) return '—'
  const digits = opts.digits ?? (Number.isInteger(n) ? 0 : 2)
  return n.toLocaleString('zh-CN', { minimumFractionDigits: digits, maximumFractionDigits: 2 })
}

/** 金额 + ¥ */
export function fmtYuan(v) {
  const n = Number(v)
  if (!isFinite(n)) return '¥—'
  return '¥' + fmtMoney(n)
}

/** 百分比：0.08 → 8.0% 或直接传百分数 90 → 90.0% */
export function fmtPct(v, digits = 1) {
  const n = Number(v)
  if (!isFinite(n)) return '—'
  if (Math.abs(n) <= 1 && Math.abs(n) > 0 && String(v).includes('.')) return (n * 100).toFixed(digits) + '%'
  // 兼容已传百分数（如 90.00）
  if (Math.abs(n) <= 1) return (n * 100).toFixed(digits) + '%'
  return n.toFixed(digits) + '%'
}

/** 环比涨跌：0.08 → ▲ 8.0%；负值 → ▼ */
export function fmtDelta(v, { suffix = '' } = {}) {
  const n = Number(v)
  if (!isFinite(n) || n === 0) return '—'
  const sign = n > 0 ? '▲' : '▼'
  const cls = n >= 0 ? 'up' : 'down'
  return { html: `<span class="${cls}">${sign} ${Math.abs(n * 100).toFixed(1)}%${suffix}</span>`, cls }
}

/** 月份标签：2026-08 → 8月 */
export function monthLabel(month) {
  if (!month) return ''
  const [y, m] = String(month).split('-')
  return `${Number(m)}月`
}

/** 月份加 N 月 */
export function addMonths(month, n) {
  const [y, m] = String(month).split('-').map(Number)
  const d = new Date(y, m - 1 + n, 1)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}

/** 月内的天数（2026-08 → 31） */
export function daysInMonth(month) {
  const [y, m] = String(month).split('-').map(Number)
  return new Date(y, m, 0).getDate()
}

/** 生成月份内所有日期 'YYYY-MM-DD' */
export function monthDates(month) {
  const n = daysInMonth(month)
  const arr = []
  for (let d = 1; d <= n; d++) {
    arr.push(`${month}-${String(d).padStart(2, '0')}`)
  }
  return arr
}

/** 星期几 0=周日 */
export function weekday(dateStr) {
  return new Date(dateStr + 'T00:00:00').getDay()
}

/** 是否周末 */
export function isWeekend(dateStr) {
  const w = weekday(dateStr)
  return w === 6 || w === 0
}

/** 日均/简单均值 */
export function avg(arr) {
  if (!arr.length) return 0
  return arr.reduce((a, b) => a + (Number(b) || 0), 0) / arr.length
}
