<template>
  <div id="app">
    <AppNav />
    <main>
      <router-view />
    </main>
  </div>
</template>

<script>
import AppNav from '@/components/AppNav.vue'

export default {
  name: 'App',
  components: { AppNav }
}
</script>

<style lang="scss">
* {
  box-sizing: border-box;
  margin: 0;
  padding: 0;
}

html {
  min-width: 320px;
  background: #f5f5f5;
}

body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC',
    'Hiragino Sans GB', 'Microsoft YaHei', sans-serif;
  background: #f5f5f5;
  color: #171717;
  -webkit-font-smoothing: antialiased;
  text-rendering: optimizeLegibility;
}

button,
input {
  font: inherit;
}

button {
  -webkit-tap-highlight-color: transparent;
}
</style>
