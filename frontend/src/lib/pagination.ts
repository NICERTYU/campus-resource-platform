import { reactive } from 'vue'
export function usePagination(pageSize = 50) {
  const state = reactive({ page: 1, pageSize, rowCount: 0 })
  return Object.assign(state, {
    query(target = state.page) {
      return new URLSearchParams({
        page: String(target),
        pageSize: String(state.pageSize),
      }).toString()
    },
    accept(target: number, rowCount: number) {
      if (!Number.isInteger(target) || target < 1) return
      state.page = target
      state.rowCount = rowCount
    },
    reset() {
      state.page = 1
      state.rowCount = 0
    },
  })
}
