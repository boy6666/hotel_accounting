import { defineStore } from 'pinia'

// 全局月份（YYYY-MM）。顶栏 MonthPicker 读写，各"月"维度页面 watch。
// 默认取真实当前月；localStorage 已有值优先（用户手动选过的月份）。
function defaultMonth() {
  const saved = localStorage.getItem('ha.month')
  if (saved && /^\d{4}-\d{2}$/.test(saved)) return saved
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}

export const useMonthStore = defineStore('month', {
  state: () => ({ month: defaultMonth() }),
  actions: {
    setMonth(m) {
      this.month = m
      localStorage.setItem('ha.month', m)
    }
  }
})
