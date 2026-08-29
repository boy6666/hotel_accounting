import { defineStore } from 'pinia'

// 导入流程全局态（顶部「导入」按钮打开 ImportDialog，各页复用）
// 状态机：upload → preview → mapping → confirm；另含 history（12.7）
// 默认月份 = 真实当前月（本地时区），不写死演示月
function currentMonth() {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}

export const useImportStore = defineStore('import', {
  state: () => ({
    open: false,
    step: 'upload',          // upload | preview | mapping | confirm | history
    month: currentMonth(),
    fileName: '',
    batchId: null,
    batch: null,             // 12.2 上传响应
    preview: null,           // 12.4 三 Sheet 预览
    mappings: null,          // 12.5 归类建议
    confirmed: null          // 12.6 确认结果
  }),
  actions: {
    openDialog(step = 'upload', month) {
      this.open = true
      this.step = step
      if (month) this.month = month
    },
    close() {
      this.open = false
      // 保留内容便于回看；下次打开重走
    },
    reset() {
      this.step = 'upload'
      this.fileName = ''
      this.batchId = null
      this.batch = null
      this.preview = null
      this.mappings = null
      this.confirmed = null
    }
  }
})
