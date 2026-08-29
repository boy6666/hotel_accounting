import { defineStore } from 'pinia'

function initialTheme() {
  const saved = localStorage.getItem('ha.theme')
  return saved === 'dark' || saved === 'light' ? saved : 'light'
}

// 主题 store：持久化 localStorage；切换后写 root dataset.theme（CSS 变量联动），
// 图表色板由 usePalette 读取本 store 重建 option（ChartView watch option 自动重绘）。
export const useThemeStore = defineStore('theme', {
  state: () => ({ theme: initialTheme() }),
  actions: {
    apply() {
      document.documentElement.dataset.theme = this.theme
    },
    toggle() {
      this.theme = this.theme === 'dark' ? 'light' : 'dark'
      localStorage.setItem('ha.theme', this.theme)
      this.apply()
    },
    setTheme(t) {
      this.theme = t
      localStorage.setItem('ha.theme', t)
      this.apply()
    }
  }
})
