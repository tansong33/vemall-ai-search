<template>
  <div class="search-page">
    <SearchBar v-model="query" @search="handleSearch" />

    <div v-if="errorMessage" class="search-page__error">
      {{ errorMessage }}
    </div>

    <div v-if="loading" class="search-page__loading">
      <div class="loading-spinner"></div>
      <span>搜索中...</span>
    </div>

    <div v-if="searchResult" class="search-page__content" :class="{ 'search-page__content--loading': loading }">
      <div v-if="searchResult.degraded" class="search-page__degraded">
        <strong>搜索服务正在降级运行</strong>
        <span v-if="searchResult.degradeReasons && searchResult.degradeReasons.length">
          {{ searchResult.degradeReasons.join('、') }}
        </span>
      </div>

      <div class="search-page__main-layout">
        <FilterSidebar
          :brands="brandFacets"
          :categories="categoryFacets"
          @filter-change="handleFilterChange"
        />

        <div class="search-page__right">
          <div class="search-page__sort-bar">
            <div class="sort-summary">
              <span>共 {{ searchResult.total || 0 }} 件商品</span>
              <span v-if="searchResult.rawTotal > searchResult.total" class="sort-summary__dedup">
                已从 {{ searchResult.rawTotal }} 条原始结果中按 SPU 去重
              </span>
            </div>
            <div class="sort-options" aria-label="排序方式">
              <button
                v-for="option in sortOptions"
                :key="option.value"
                type="button"
                class="sort-btn"
                :class="{ 'sort-btn--active': sortField === option.value }"
                @click="handleSort(option.value)"
              >
                {{ option.label }}
              </button>
            </div>
          </div>

          <div v-if="resultItems.length" class="search-page__grid">
            <ProductCard
              v-for="product in resultItems"
              :key="product.skuId || product.spuId || product.title"
              :product="product"
            />
          </div>

          <div v-else class="search-page__empty">
            <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="#c7c7cc" stroke-width="1.5">
              <circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/>
            </svg>
            <p>未找到相关商品</p>
            <span>试试其他关键词，如“公牛插座”或“华为手机”</span>
          </div>

          <nav v-if="totalPages > 1" class="pagination" aria-label="搜索结果分页">
            <button
              type="button"
              class="pagination__button"
              :disabled="currentPage === 1"
              @click="goToPage(currentPage - 1)"
            >
              上一页
            </button>
            <button
              v-for="pageNumber in pageNumbers"
              :key="pageNumber"
              type="button"
              class="pagination__page"
              :class="{ 'pagination__page--active': currentPage === pageNumber }"
              :aria-current="currentPage === pageNumber ? 'page' : null"
              @click="goToPage(pageNumber)"
            >
              {{ pageNumber }}
            </button>
            <button
              type="button"
              class="pagination__button"
              :disabled="currentPage === totalPages"
              @click="goToPage(currentPage + 1)"
            >
              下一页
            </button>
          </nav>
        </div>
      </div>
    </div>

    <div v-if="!searchResult && !loading && !errorMessage" class="search-page__welcome">
      <div class="welcome-icon">
        <svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="#0071e3" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round">
          <circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/>
        </svg>
      </div>
      <h2>AI 智能搜索</h2>
      <p>输入商品名称、品牌或关键词开始搜索</p>
      <p class="welcome-hint">实体识别 · 查询理解 · Elasticsearch 检索</p>
    </div>
  </div>
</template>

<script>
import { search, SORT_MAP } from '@/api/search'
import SearchBar from '@/components/SearchBar.vue'
import ProductCard from '@/components/ProductCard.vue'
import FilterSidebar from '@/components/FilterSidebar.vue'

const PAGE_SIZE = 20

export default {
  name: 'SearchPage',
  components: { SearchBar, ProductCard, FilterSidebar },
  data() {
    return {
      query: '',
      loading: false,
      errorMessage: '',
      searchResult: null,
      requestSequence: 0,
      currentPage: 1,
      sortField: 'default',
      activeFilters: {
        brands: [],
        categories: [],
        minPriceFen: null,
        maxPriceFen: null,
        inStock: null
      },
      sortOptions: [
        { label: '综合排序', value: 'default' },
        { label: '价格升序', value: 'price_asc' },
        { label: '价格降序', value: 'price_desc' },
        { label: '销量优先', value: 'sales' },
        { label: '评分优先', value: 'rating' }
      ]
    }
  },
  computed: {
    resultItems() {
      return this.searchResult && Array.isArray(this.searchResult.items)
        ? this.searchResult.items
        : []
    },
    brandFacets() {
      const facets = this.searchResult && this.searchResult.facets
      return facets && Array.isArray(facets.brands) ? facets.brands : []
    },
    categoryFacets() {
      const facets = this.searchResult && this.searchResult.facets
      return facets && Array.isArray(facets.categories) ? facets.categories : []
    },
    totalPages() {
      if (!this.searchResult || !this.searchResult.total) return 0
      return Math.ceil(this.searchResult.total / PAGE_SIZE)
    },
    pageNumbers() {
      const visibleCount = 5
      let start = Math.max(1, this.currentPage - Math.floor(visibleCount / 2))
      const end = Math.min(this.totalPages, start + visibleCount - 1)
      start = Math.max(1, end - visibleCount + 1)
      const pages = []
      for (let page = start; page <= end; page += 1) {
        pages.push(page)
      }
      return pages
    }
  },
  methods: {
    async handleSearch(selectedQuery) {
      if (typeof selectedQuery === 'string') {
        this.query = selectedQuery
      }
      this.currentPage = 1
      await this.loadResults()
    },
    async loadResults() {
      const normalizedQuery = this.query.trim()
      if (!normalizedQuery) return

      const requestId = ++this.requestSequence
      const requestedPage = this.currentPage
      this.loading = true
      this.errorMessage = ''
      try {
        const data = await search({
          query: normalizedQuery,
          page: requestedPage,
          pageSize: PAGE_SIZE,
          sort: SORT_MAP[this.sortField] || 'RELEVANCE',
          filters: {
            brands: this.activeFilters.brands.slice(),
            categories: this.activeFilters.categories.slice(),
            minPriceFen: this.activeFilters.minPriceFen,
            maxPriceFen: this.activeFilters.maxPriceFen,
            inStock: this.activeFilters.inStock
          }
        })
        if (requestId !== this.requestSequence) return false
        this.searchResult = data || {
          total: 0,
          rawTotal: 0,
          page: requestedPage,
          pageSize: PAGE_SIZE,
          items: [],
          facets: null
        }
        if (this.searchResult.page && this.searchResult.page > 0) {
          this.currentPage = this.searchResult.page
        }
        return true
      } catch (error) {
        if (requestId !== this.requestSequence) return false
        this.errorMessage = error.message || '搜索失败'
        return false
      } finally {
        if (requestId === this.requestSequence) {
          this.loading = false
        }
      }
    },
    async handleSort(field) {
      if (this.sortField === field) return
      this.sortField = field
      this.currentPage = 1
      await this.loadResults()
    },
    async handleFilterChange(filters) {
      this.activeFilters = filters
      this.currentPage = 1
      await this.loadResults()
    },
    async goToPage(page) {
      if (page < 1 || page > this.totalPages || page === this.currentPage) return
      const previousPage = this.currentPage
      this.currentPage = page
      const loaded = await this.loadResults()
      if (!loaded && this.currentPage === page) {
        this.currentPage = previousPage
      }
      if (loaded) {
        window.scrollTo({ top: 0, behavior: 'smooth' })
      }
    }
  }
}
</script>

<style lang="scss" scoped>
.search-page {
  max-width: $page-max-width;
  margin: 0 auto;
  padding: 0 24px 60px;
  min-height: calc(100vh - 60px);

  &__loading {
    display: flex; align-items: center; justify-content: center;
    gap: 12px; padding: 60px 0; color: #86868b; font-size: 15px;
  }
  &__content {
    margin-top: 4px; transition: opacity 0.2s;
    &--loading { opacity: 0.55; pointer-events: none; }
  }
  &__error, &__degraded {
    padding: 10px 16px; margin-bottom: 12px; border-radius: 10px; font-size: 13px;
  }
  &__error {
    color: #b42318; background: #fef3f2; border: 1px solid #fecdca;
  }
  &__degraded {
    display: flex; gap: 8px; flex-wrap: wrap;
    color: #854d0e; background: #fefce8; border: 1px solid #fde68a;
  }
  &__main-layout { display: flex; gap: 20px; align-items: flex-start; }
  &__right { flex: 1; min-width: 0; }
  &__sort-bar {
    display: flex; align-items: center; justify-content: space-between; gap: 16px;
    padding: 12px 0; margin-bottom: 16px;
    border-bottom: 1px solid rgba(0, 0, 0, 0.06);
  }
  &__grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
    gap: 16px;
  }
  &__empty {
    display: flex; flex-direction: column; align-items: center;
    padding: 80px 20px; color: #86868b;
    p { font-size: 17px; font-weight: 500; color: #1d1d1f; margin-top: 16px; }
    span { font-size: 14px; margin-top: 8px; }
  }
  &__welcome {
    display: flex; flex-direction: column; align-items: center;
    padding: 100px 20px 60px; text-align: center;
    .welcome-icon {
      width: 96px; height: 96px;
      display: flex; align-items: center; justify-content: center;
      background: rgba(0, 122, 255, 0.06); border-radius: 24px; margin-bottom: 24px;
    }
    h2 { font-size: 28px; font-weight: 700; color: #1d1d1f; letter-spacing: -0.02em; margin-bottom: 8px; }
    p { font-size: 17px; color: #86868b; }
    .welcome-hint { font-size: 14px; color: #a1a1a6; margin-top: 12px; }
  }
}
.sort-summary {
  display: flex; flex-direction: column; gap: 3px; font-size: 14px; color: #86868b;
  &__dedup { font-size: 11px; color: #a1a1a6; }
}
.sort-options { display: flex; gap: 4px; flex-wrap: wrap; justify-content: flex-end; }
.sort-btn {
  padding: 6px 14px; border: none; border-radius: 8px;
  background: transparent; color: #86868b; font-size: 13px;
  cursor: pointer; transition: all 0.2s; font-family: inherit;
  &:hover { background: rgba(0, 0, 0, 0.04); color: #1d1d1f; }
  &--active { background: rgba(0, 122, 255, 0.08); color: #0071e3; font-weight: 500; }
}
.pagination {
  display: flex; align-items: center; justify-content: center; gap: 8px; margin-top: 28px;
  &__button, &__page {
    min-width: 38px; height: 36px; padding: 0 12px; border-radius: 9px;
    border: 1px solid rgba(0, 0, 0, 0.1); background: white; color: #1d1d1f;
    cursor: pointer; font-family: inherit;
    &:hover:not(:disabled) { border-color: #0071e3; color: #0071e3; }
    &:disabled { color: #c7c7cc; cursor: not-allowed; }
  }
  &__page { padding: 0; }
  &__page--active { border-color: #0071e3; background: #0071e3; color: white; }
}
.loading-spinner {
  width: 24px; height: 24px;
  border: 2.5px solid rgba(0, 122, 255, 0.15);
  border-top-color: #0071e3; border-radius: 50%;
  animation: spin 0.8s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }

@media (max-width: 800px) {
  .search-page {
    padding: 0 16px 40px;
    &__main-layout { flex-direction: column; }
    &__right { width: 100%; }
    &__sort-bar { align-items: flex-start; flex-direction: column; }
  }
  .sort-options { justify-content: flex-start; }
}
</style>
