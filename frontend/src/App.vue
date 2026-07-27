<template>
  <div id="app">
    <header class="app-header">
      <div class="app-header__inner">
        <a class="app-header__brand" :href="searchHref">AI Search</a>
        <nav class="app-header__nav" aria-label="主导航">
          <a :href="searchHref" :class="{ 'app-header__link--active': !isDebugPage }">商品搜索</a>
          <a :href="debugHref" :class="{ 'app-header__link--active': isDebugPage }">链路调试</a>
        </nav>
      </div>
    </header>
    <SearchPage v-if="!isDebugPage" />
    <PipelineView v-else />
  </div>
</template>

<script>
import SearchPage from '@/views/SearchPage.vue'
import PipelineView from '@/components/PipelineView.vue'

function normalizeBasePath(value) {
  if (!value || value === '/') return ''
  return '/' + value.replace(/^\/+|\/+$/g, '')
}

function normalizeRoutePath(value) {
  const normalized = '/' + String(value || '').replace(/^\/+|\/+$/g, '')
  return normalized === '/' ? '/' : normalized
}

const basePath = normalizeBasePath(process.env.BASE_URL)

export default {
  name: 'App',
  components: { SearchPage, PipelineView },
  data() {
    return {
      currentPath: normalizeRoutePath(window.location.pathname),
      searchHref: basePath + '/',
      debugHref: basePath + '/debug'
    }
  },
  computed: {
    isDebugPage() {
      return this.currentPath === normalizeRoutePath(this.debugHref)
    }
  }
}
</script>

<style lang="scss">
* { margin: 0; padding: 0; box-sizing: border-box; }
body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC',
    'Hiragino Sans GB', 'Microsoft YaHei', sans-serif;
  background: #f5f5f5;
  color: #333;
}
button, input { font: inherit; }
.app-header {
  position: sticky; top: 0; z-index: 20;
  border-bottom: 1px solid rgba(0, 0, 0, 0.06);
  background: rgba(255, 255, 255, 0.88);
  backdrop-filter: blur(18px); -webkit-backdrop-filter: blur(18px);

  &__inner {
    display: flex; align-items: center; justify-content: space-between;
    max-width: $page-max-width; height: 56px; margin: 0 auto; padding: 0 24px;
  }
  &__brand {
    color: #1d1d1f; font-size: 17px; font-weight: 700; text-decoration: none;
    letter-spacing: -0.02em;
  }
  &__nav { display: flex; gap: 4px; }
  &__nav a {
    padding: 7px 13px; border-radius: 8px;
    color: #86868b; font-size: 13px; text-decoration: none;
    transition: all 0.2s;
    &:hover { background: rgba(0, 0, 0, 0.04); color: #1d1d1f; }
  }
  &__link--active {
    background: rgba(0, 122, 255, 0.08) !important;
    color: #0071e3 !important; font-weight: 600;
  }
}

@media (max-width: 520px) {
  .app-header {
    &__inner { padding: 0 16px; }
    &__nav a { padding: 7px 9px; }
  }
}
</style>
