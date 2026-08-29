import { defineStore } from 'pinia'

let seq = 0

// Toast：成功/错误/警告/降级，右侧浮层，自动消失。
export const useToastStore = defineStore('toast', {
  state: () => ({ items: [] }),
  actions: {
    push(message, type = 'info', opts = {}) {
      const id = ++seq
      this.items.push({ id, message, type, sticky: !!opts.sticky })
      if (!opts.sticky) {
        setTimeout(() => this.dismiss(id), 3200)
      }
      return id
    },
    success(m) { return this.push(m, 'success') },
    error(m) { return this.push(m, 'error') },
    warn(m) { return this.push(m, 'warn') },
    /** 降级提示（旁车不可用 50100 / LLM 失败 50200） */
    degrade(m) { return this.push(m, 'degrade', { sticky: true }) },
    dismiss(id) {
      this.items = this.items.filter((t) => t.id !== id)
    }
  }
})
