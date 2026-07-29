<template>
  <aside class="filter-sidebar">
    <div class="filter-sidebar__header">
      <h3>筛选</h3>
      <button v-if="hasActiveFilters" type="button" class="filter-sidebar__clear" @click="clearAll">
        清除全部
      </button>
    </div>

    <div v-if="brands.length" class="filter-section">
      <button type="button" class="filter-section__title" @click="toggleSection('brands')">
        品牌
        <span class="filter-section__count">({{ brands.length }})</span>
        <span class="filter-section__arrow" :class="{ 'filter-section__arrow--collapsed': !sections.brands }">▾</span>
      </button>
      <div v-show="sections.brands" class="filter-section__body">
        <label
          v-for="brand in brands"
          :key="brand.key"
          class="filter-checkbox"
          :class="{ 'filter-checkbox--active': selectedBrands.includes(brand.key) }"
        >
          <input
            type="checkbox"
            :value="brand.key"
            :checked="selectedBrands.includes(brand.key)"
            @change="toggleBrand(brand.key)"
          />
          <span class="filter-checkbox__label">{{ brand.key }}</span>
          <span class="filter-checkbox__count">{{ brand.count }}</span>
        </label>
      </div>
    </div>

    <div v-if="categories.length" class="filter-section">
      <button type="button" class="filter-section__title" @click="toggleSection('categories')">
        品类
        <span class="filter-section__count">({{ categories.length }})</span>
        <span class="filter-section__arrow" :class="{ 'filter-section__arrow--collapsed': !sections.categories }">▾</span>
      </button>
      <div v-show="sections.categories" class="filter-section__body">
        <label
          v-for="category in categories"
          :key="category.key"
          class="filter-checkbox"
          :class="{ 'filter-checkbox--active': selectedCategories.includes(category.key) }"
        >
          <input
            type="checkbox"
            :value="category.key"
            :checked="selectedCategories.includes(category.key)"
            @change="toggleCategory(category.key)"
          />
          <span class="filter-checkbox__label">{{ category.key }}</span>
          <span class="filter-checkbox__count">{{ category.count }}</span>
        </label>
      </div>
    </div>

    <div class="filter-section">
      <h4 class="filter-section__heading">价格区间</h4>
      <div class="filter-section__body">
        <label
          v-for="option in priceOptions"
          :key="option.value"
          class="filter-radio"
          :class="{ 'filter-radio--active': selectedPriceKey === option.value }"
        >
          <input
            type="radio"
            name="price"
            :value="option.value"
            :checked="selectedPriceKey === option.value"
            @change="selectPrice(option.value)"
          />
          <span class="filter-radio__label">{{ option.label }}</span>
        </label>
      </div>
    </div>

    <div class="filter-section">
      <h4 class="filter-section__heading">库存状态</h4>
      <div class="filter-section__body">
        <label class="filter-checkbox" :class="{ 'filter-checkbox--active': inStockOnly }">
          <input type="checkbox" :checked="inStockOnly" @change="toggleInStock" />
          <span class="filter-checkbox__label">仅显示有库存</span>
        </label>
      </div>
    </div>
  </aside>
</template>

<script setup>
import { ref, reactive, computed } from 'vue'

defineProps({
  brands: { type: Array, default: () => [] },
  categories: { type: Array, default: () => [] }
})

const emit = defineEmits(['filter-change'])

const PRICE_RANGES = {
  all: { minPriceFen: null, maxPriceFen: null },
  '0-50': { minPriceFen: null, maxPriceFen: 5000 },
  '50-100': { minPriceFen: 5000, maxPriceFen: 10000 },
  '100-500': { minPriceFen: 10000, maxPriceFen: 50000 },
  '500-2000': { minPriceFen: 50000, maxPriceFen: 200000 },
  '2000+': { minPriceFen: 200000, maxPriceFen: null }
}

const priceOptions = [
  { label: '全部价格', value: 'all' },
  { label: '0 - 50 元', value: '0-50' },
  { label: '50 - 100 元', value: '50-100' },
  { label: '100 - 500 元', value: '100-500' },
  { label: '500 - 2000 元', value: '500-2000' },
  { label: '2000 元以上', value: '2000+' }
]

const selectedBrands = ref([])
const selectedCategories = ref([])
const selectedPriceKey = ref('all')
const inStockOnly = ref(false)
const sections = reactive({ brands: true, categories: true })

const hasActiveFilters = computed(() =>
  selectedBrands.value.length > 0 ||
  selectedCategories.value.length > 0 ||
  selectedPriceKey.value !== 'all' ||
  inStockOnly.value
)

function toggleSection(name) {
  sections[name] = !sections[name]
}

function toggleBrand(brand) {
  selectedBrands.value = selectedBrands.value.includes(brand)
    ? selectedBrands.value.filter(item => item !== brand)
    : [...selectedBrands.value, brand]
  emitFilters()
}

function toggleCategory(category) {
  selectedCategories.value = selectedCategories.value.includes(category)
    ? selectedCategories.value.filter(item => item !== category)
    : [...selectedCategories.value, category]
  emitFilters()
}

function selectPrice(key) {
  selectedPriceKey.value = key
  emitFilters()
}

function toggleInStock() {
  inStockOnly.value = !inStockOnly.value
  emitFilters()
}

function clearAll() {
  selectedBrands.value = []
  selectedCategories.value = []
  selectedPriceKey.value = 'all'
  inStockOnly.value = false
  emitFilters()
}

function emitFilters() {
  const selectedPrice = PRICE_RANGES[selectedPriceKey.value]
  emit('filter-change', {
    brands: selectedBrands.value.slice(),
    categories: selectedCategories.value.slice(),
    minPriceFen: selectedPrice.minPriceFen,
    maxPriceFen: selectedPrice.maxPriceFen,
    inStock: inStockOnly.value ? true : null
  })
}
</script>

<style lang="scss" scoped>
.filter-sidebar {
  width: 220px; flex-shrink: 0;
  background: rgba(255, 255, 255, 0.7);
  backdrop-filter: blur(20px); -webkit-backdrop-filter: blur(20px);
  border: 1px solid rgba(0, 0, 0, 0.06); border-radius: 14px;
  padding: 16px; position: sticky; top: 72px;
  max-height: calc(100vh - 92px); overflow-y: auto;

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
  &__title, &__heading {
    display: flex; align-items: center; gap: 4px; width: 100%;
    padding: 0; border: 0; background: transparent;
    font-size: 13px; font-weight: 600; color: #1d1d1f;
    margin-bottom: 8px; font-family: inherit; text-align: left;
  }
  &__title { cursor: pointer; user-select: none; }
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

@media (max-width: 800px) {
  .filter-sidebar {
    width: 100%; position: static; max-height: none;
    display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 16px;
    &__header { grid-column: 1 / -1; }
  }
}

@media (max-width: 520px) {
  .filter-sidebar { display: block; }
}
</style>
