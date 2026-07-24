<template>
  <aside class="filter-sidebar">
    <div class="filter-sidebar__header">
      <h3>筛选</h3>
      <button v-if="hasActiveFilters" class="filter-sidebar__clear" @click="clearAll">清除全部</button>
    </div>

    <!-- 品牌筛选 -->
    <div v-if="brands && brands.length" class="filter-section">
      <h4 class="filter-section__title" @click="toggleSection('brands')">
        品牌
        <span class="filter-section__count">({{ brands.length }})</span>
        <span class="filter-section__arrow" :class="{ 'filter-section__arrow--collapsed': !sections.brands }">▾</span>
      </h4>
      <div v-show="sections.brands" class="filter-section__body">
        <label
          v-for="brand in brands"
          :key="brand.key"
          class="filter-checkbox"
          :class="{ 'filter-checkbox--active': selectedBrands.includes(brand.key) }"
        >
          <input type="checkbox" :value="brand.key" :checked="selectedBrands.includes(brand.key)" @change="toggleBrand(brand.key)" />
          <span class="filter-checkbox__label">{{ brand.key }}</span>
          <span class="filter-checkbox__count">{{ brand.count }}</span>
        </label>
      </div>
    </div>

    <!-- 品类筛选 -->
    <div v-if="categories && categories.length" class="filter-section">
      <h4 class="filter-section__title" @click="toggleSection('categories')">
        品类
        <span class="filter-section__count">({{ categories.length }})</span>
        <span class="filter-section__arrow" :class="{ 'filter-section__arrow--collapsed': !sections.categories }">▾</span>
      </h4>
      <div v-show="sections.categories" class="filter-section__body">
        <label
          v-for="cat in categories"
          :key="cat.key"
          class="filter-checkbox"
          :class="{ 'filter-checkbox--active': selectedCategories.includes(cat.key) }"
        >
          <input type="checkbox" :value="cat.key" :checked="selectedCategories.includes(cat.key)" @change="toggleCategory(cat.key)" />
          <span class="filter-checkbox__label">{{ cat.key }}</span>
          <span class="filter-checkbox__count">{{ cat.count }}</span>
        </label>
      </div>
    </div>

    <!-- 价格筛选 -->
    <div class="filter-section">
      <h4 class="filter-section__title">价格区间</h4>
      <div class="filter-section__body">
        <label
          v-for="range in priceRanges"
          :key="range.value"
          class="filter-radio"
          :class="{ 'filter-radio--active': selectedPriceRange === range.value }"
        >
          <input type="radio" name="price" :value="range.value" :checked="selectedPriceRange === range.value" @change="selectPriceRange(range.value)" />
          <span class="filter-radio__label">{{ range.label }}</span>
        </label>
      </div>
    </div>
  </aside>
</template>

<script setup>
import { ref, reactive, computed } from 'vue'

const props = defineProps({
  brands: { type: Array, default: () => [] },
  categories: { type: Array, default: () => [] }
})

const emit = defineEmits(['filter-change'])

const selectedBrands = ref([])
const selectedCategories = ref([])
const selectedPriceRange = ref('all')

const sections = reactive({ brands: true, categories: true })

const priceRanges = [
  { label: '全部价格', value: 'all' },
  { label: '0 - 50', value: '0-50' },
  { label: '50 - 100', value: '50-100' },
  { label: '100 - 500', value: '100-500' },
  { label: '500 - 2000', value: '500-2000' },
  { label: '2000 以上', value: '2000+' },
]

const hasActiveFilters = computed(() =>
  selectedBrands.value.length > 0 || selectedCategories.value.length > 0 || selectedPriceRange.value !== 'all'
)

function toggleSection(name) { sections[name] = !sections[name] }

function toggleBrand(brand) {
  const idx = selectedBrands.value.indexOf(brand)
  if (idx >= 0) selectedBrands.value.splice(idx, 1)
  else selectedBrands.value.push(brand)
  emitFilters()
}

function toggleCategory(cat) {
  const idx = selectedCategories.value.indexOf(cat)
  if (idx >= 0) selectedCategories.value.splice(idx, 1)
  else selectedCategories.value.push(cat)
  emitFilters()
}

function selectPriceRange(range) { selectedPriceRange.value = range; emitFilters() }

function clearAll() {
  selectedBrands.value = []
  selectedCategories.value = []
  selectedPriceRange.value = 'all'
  emitFilters()
}

function emitFilters() {
  emit('filter-change', {
    brands: selectedBrands.value,
    categories: selectedCategories.value,
    priceRange: selectedPriceRange.value
  })
}
</script>

<style lang="scss" scoped>
.filter-sidebar {
  width: 220px; flex-shrink: 0;
  background: rgba(255, 255, 255, 0.7);
  backdrop-filter: blur(20px); -webkit-backdrop-filter: blur(20px);
  border: 1px solid rgba(0, 0, 0, 0.06); border-radius: 14px;
  padding: 16px; position: sticky; top: 20px;
  max-height: calc(100vh - 40px); overflow-y: auto;

  &__header {
    display: flex; align-items: center; justify-content: space-between;
    margin-bottom: 16px; padding-bottom: 12px;
    border-bottom: 1px solid rgba(0, 0, 0, 0.06);
    h3 { font-size: 15px; font-weight: 600; color: #1d1d1f; }
  }
  &__clear {
    font-size: 12px; color: #0071e3; background: none; border: none;
    cursor: pointer; font-family: inherit;
    &:hover { text-decoration: underline; }
  }
}

.filter-section {
  margin-bottom: 16px;
  &__title {
    display: flex; align-items: center; gap: 4px;
    font-size: 13px; font-weight: 600; color: #1d1d1f;
    cursor: pointer; margin-bottom: 8px; user-select: none;
  }
  &__count { font-weight: 400; color: #86868b; font-size: 12px; }
  &__arrow {
    margin-left: auto; font-size: 12px; color: #86868b;
    transition: transform 0.2s;
    &--collapsed { transform: rotate(-90deg); }
  }
  &__body { display: flex; flex-direction: column; gap: 2px; }
}

.filter-checkbox, .filter-radio {
  display: flex; align-items: center; gap: 8px;
  padding: 6px 8px; border-radius: 8px; cursor: pointer;
  transition: background 0.15s; font-size: 13px;
  input { display: none; }
  &:hover { background: rgba(0, 0, 0, 0.03); }
  &--active {
    background: rgba(0, 122, 255, 0.06);
    .filter-checkbox__label, .filter-radio__label { color: #0071e3; font-weight: 500; }
  }
  &__label { flex: 1; color: #1d1d1f; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  &__count { font-size: 11px; color: #86868b; flex-shrink: 0; }
}

.filter-checkbox::before {
  content: ''; width: 16px; height: 16px; border-radius: 4px; flex-shrink: 0;
  border: 1.5px solid #c7c7cc; transition: all 0.15s;
}
.filter-checkbox--active::before {
  background: #0071e3; border-color: #0071e3;
  box-shadow: inset 0 0 0 2px white;
}

.filter-radio::before {
  content: ''; width: 16px; height: 16px; border-radius: 50%; flex-shrink: 0;
  border: 1.5px solid #c7c7cc; transition: all 0.15s;
}
.filter-radio--active::before {
  border-color: #0071e3;
  box-shadow: inset 0 0 0 3px white, 0 0 0 1.5px #0071e3;
}
</style>
