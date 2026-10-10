<script setup lang="ts">
const props = defineProps<{ page: number; pageSize: number; rowCount: number; loading: boolean }>()
const emit = defineEmits<{ change: [page: number] }>()
function change(target: number) {
  if (props.loading || target < 1 || !Number.isInteger(target)) return
  if (target > props.page && props.rowCount < props.pageSize) return
  emit('change', target)
}
</script>
<template>
  <nav class="pagination-bar" aria-label="列表分页">
    <span
      >第 {{ page }} 页 · 本页 {{ rowCount }} 条 <small>每页 {{ pageSize }} 条</small></span
    >
    <div>
      <el-button :disabled="loading || page <= 1" @click="change(page - 1)">上一页</el-button
      ><el-button :disabled="loading || rowCount < pageSize" @click="change(page + 1)"
        >下一页</el-button
      >
    </div>
  </nav>
</template>
