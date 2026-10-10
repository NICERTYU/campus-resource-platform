<script setup lang="ts">
defineProps<{ eyebrow: string; title: string; description: string }>()
</script>
<template>
  <header class="page-heading">
    <div>
      <p class="eyebrow">{{ eyebrow }}</p>
      <h1>{{ title }}</h1>
      <p class="page-description">{{ description }}</p>
    </div>
    <div class="heading-action"><slot /></div>
  </header>
</template>
