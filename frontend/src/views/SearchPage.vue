<template>
  <div class="search-page">
    <SearchBar v-model="query" @search="handleSearch" />
    <PipelineView v-if="pipelineData" :data="pipelineData" />

    <div v-if="loading" class="search-page__loading">
      <div class="loading-spinner"></div>
      <span>搜索中...</span>
    </div>

    <div v-if="searchResult && !loading" class="search-page__content">
      <!-- 左侧筛选 + 右侧商品列表 -->
      <div class="search-page__main-layout">
        <FilterSidebar
          :brands="searchResult.aggregations?.brands || []"
          :categories="searchResult.aggregations?.categories || []"
          @filter-change="handleFilterChange"
        />

        <div class="search-page__right">
          <div class="search-page__sort-bar">
            <span class="sort-total">共 {{ searchResult.total }} 件商品</span>
            <div class="sort-options">
              <button v-for="opt in sortOptions" :key="opt.value"
                class="sort-btn" :class="{ 'sort-btn--active': sortField === opt.value }"
                @click="handleSort(opt.value)">{{ opt.label }}</button>
            </div>
          </div>

          <div v-if="searchResult.products && searchResult.products.length" class="search-page__grid">
            <ProductCard v-for="product in searchResult.products"
              :key="product.sku_id || product.id" :product="product" />
          </div>

          <div v-else class="search-page__empty">
            <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="#c7c7cc" stroke-width="1.5">
              <circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/>
            </svg>
            <p>未找到相关商品</p>
            <span>试试其他关键词，如"公牛插座"、"华为手机"</span>
          </div>
        </div>
      </div>
    </div>

    <div v-if="!searchResult && !loading && !pipelineData" class="search-page__welcome">
      <div class="welcome-icon">
        <svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="#0071e3" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round">
          <circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/>
        </svg>
      </div>
      <h2>AI 智能搜索</h2>
      <p>输入商品名称、品牌或关键词开始搜索</p>
      <p class="welcome-hint">NER 实体识别 · IK 分词 · 语义搜索</p>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { searchPipeline } from '@/api/search'
import SearchBar from '@/components/SearchBar.vue'
import PipelineView from '@/components/PipelineView.vue'
import ProductCard from '@/components/ProductCard.vue'
import FilterSidebar from '@/components/FilterSidebar.vue'

const query = ref('')
const loading = ref(false)
const pipelineData = ref(null)
const searchResult = ref(null)
const sortField = ref('default')
const activeFilters = ref({ brands: [], categories: [], priceRange: 'all' })

const sortOptions = [
  { label: '综合排序', value: 'default' },
  { label: '价格升序', value: 'price_asc' },
  { label: '价格降序', value: 'price_desc' },
  { label: '销量优先', value: 'sales' },
  { label: '评分优先', value: 'rating' },
]

async function handleSearch(selectedQuery) {
  if (typeof selectedQuery === 'string') {
    query.value = selectedQuery
  }
  if (!query.value.trim()) return
  loading.value = true
  searchResult.value = null
  pipelineData.value = null

  try {
    const response = await searchPipeline({
      query: query.value,
      sort: sortField.value,
      filters: activeFilters.value
    })
    pipelineData.value = {
      nerResult: response.nerResult || null,
      modelResult: response.modelResult || null,
      esAnalyzeResult: response.esAnalyzeResult || null,
      searchResult: response.searchResult || null,
      totalCostMs: response.totalCostMs || null
    }
    searchResult.value = response.searchResult || { total: 0, products: [] }
  } catch (error) {
    console.error('搜索失败:', error)
    searchResult.value = { total: 0, products: [], error: error.message }
  } finally {
    loading.value = false
  }
}

function handleSort(field) {
  sortField.value = field
  handleSearch()
}

function handleFilterChange(filters) {
  activeFilters.value = filters
  handleSearch()
}
</script>

<style lang="scss" scoped>
.search-page {
  max-width: $page-max-width;
  margin: 0 auto;
  padding: 0 24px 60px;
  min-height: 100vh;

  &__loading {
    display: flex; align-items: center; justify-content: center;
    gap: 12px; padding: 60px 0; color: #86868b; font-size: 15px;
  }
  &__content { margin-top: 4px; }
  &__main-layout { display: flex; gap: 20px; align-items: flex-start; }
  &__right { flex: 1; min-width: 0; }
  &__sort-bar {
    display: flex; align-items: center; justify-content: space-between;
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
    .welcome-hint { font-size: 14px; color: #c7c7cc; margin-top: 12px; }
  }
}
.sort-total { font-size: 14px; color: #86868b; }
.sort-options { display: flex; gap: 4px; }
.sort-btn {
  padding: 6px 16px; border: none; border-radius: 8px;
  background: transparent; color: #86868b; font-size: 13px;
  cursor: pointer; transition: all 0.2s; font-family: inherit;
  &:hover { background: rgba(0, 0, 0, 0.04); color: #1d1d1f; }
  &--active { background: rgba(0, 122, 255, 0.08); color: #0071e3; font-weight: 500; }
}
.loading-spinner {
  width: 24px; height: 24px;
  border: 2.5px solid rgba(0, 122, 255, 0.15);
  border-top-color: #0071e3; border-radius: 50%;
  animation: spin 0.8s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }
</style>
