<template>
  <div id="app">
    <SearchPage />
  </div>
</template>

<script setup>
import SearchPage from '@/views/SearchPage.vue'
</script>

<style lang="scss">
* { margin: 0; padding: 0; box-sizing: border-box; }
body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC',
    'Hiragino Sans GB', 'Microsoft YaHei', sans-serif;
  background: #f5f5f5;
  color: #333;
}
</style>
