<script setup lang="ts">
import { statusLabel, statusType } from '../lib/status'
defineProps<{ status: string; label?: string }>()
</script>
<template>
  <el-tag :type="statusType(status)" effect="light" round>{{
    label ?? statusLabel[status] ?? status
  }}</el-tag>
</template>
