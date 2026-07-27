<template>
  <article class="product-card">
    <div class="product-card__image">
      <img
        v-if="resolvedImage"
        :src="resolvedImage"
        :alt="product.title || '商品图片'"
        loading="lazy"
        @error="handleImageError"
      />
      <div v-else class="product-card__placeholder">
        <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="#c7c7cc" stroke-width="1.5">
          <rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="8.5" cy="8.5" r="1.5"/><path d="m21 15-5-5L5 21"/>
        </svg>
      </div>
      <span v-if="product.inStock === false" class="product-card__sold-out">缺货</span>
    </div>

    <div class="product-card__info">
      <h3 class="product-card__title" v-html="displayTitle"></h3>
      <div class="product-card__meta">
        <span v-if="product.brandName" class="product-card__brand">{{ product.brandName }}</span>
        <span v-if="product.categoryName" class="product-card__category">{{ product.categoryName }}</span>
      </div>
      <div class="product-card__bottom">
        <div class="product-card__price">
          <span class="price-symbol">¥</span>
          <span class="price-value">{{ formatPrice(product.priceFen) }}</span>
        </div>
        <span v-if="product.skuId" class="product-card__sku">SKU {{ product.skuId }}</span>
      </div>
    </div>
  </article>
</template>

<script>
const HTML_ENTITIES = {
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&#39;'
}

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, character => HTML_ENTITIES[character])
}

function sanitizeHighlight(value) {
  return String(value)
    .split(/(<\/?em>)/gi)
    .map(part => /^<\/?em>$/i.test(part) ? part.toLowerCase() : escapeHtml(part))
    .join('')
}

export default {
  name: 'ProductCard',
  props: {
    product: { type: Object, required: true }
  },
  data() {
    return {
      imageFailed: false
    }
  },
  computed: {
    displayTitle() {
      if (this.product.highlightTitle) {
        return sanitizeHighlight(this.product.highlightTitle)
      }
      return escapeHtml(this.product.title || '未命名商品')
    },
    resolvedImage() {
      return this.imageFailed ? null : this.product.imageUrl
    }
  },
  watch: {
    'product.imageUrl'() {
      this.imageFailed = false
    }
  },
  methods: {
    formatPrice(priceFen) {
      if (priceFen === null || priceFen === undefined) return '--'
      return (Number(priceFen) / 100).toFixed(2)
    },
    handleImageError() {
      this.imageFailed = true
    }
  }
}
</script>

<style lang="scss" scoped>
.product-card {
  background: white;
  border-radius: 14px;
  overflow: hidden;
  border: 1px solid rgba(0, 0, 0, 0.06);
  transition: all 0.3s cubic-bezier(0.25, 0.1, 0.25, 1);

  &:hover {
    transform: translateY(-3px);
    box-shadow: 0 8px 30px rgba(0, 0, 0, 0.08);
    border-color: rgba(0, 0, 0, 0.1);
  }

  &__image {
    position: relative; width: 100%; height: 200px; overflow: hidden; background: #f5f5f7;
    img { width: 100%; height: 100%; object-fit: cover; transition: transform 0.4s; }
    &:hover img { transform: scale(1.03); }
  }
  &__placeholder {
    display: flex; align-items: center; justify-content: center;
    width: 100%; height: 100%; background: #f5f5f7;
  }
  &__sold-out {
    position: absolute; top: 10px; right: 10px;
    padding: 3px 10px; border-radius: 8px;
    background: rgba(0, 0, 0, 0.58); color: white; font-size: 11px;
  }
  &__info { padding: 14px; }
  &__title {
    font-size: 14px; line-height: 1.45; font-weight: 500;
    height: 41px; overflow: hidden;
    display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical;
    color: #1d1d1f; margin-bottom: 8px;
    ::v-deep em {
      color: #0071e3; font-style: normal; font-weight: 600;
      background: rgba(0, 122, 255, 0.06); padding: 0 2px; border-radius: 3px;
    }
  }
  &__meta { display: flex; gap: 6px; min-height: 20px; margin-bottom: 10px; flex-wrap: wrap; }
  &__brand, &__category {
    font-size: 11px; color: #86868b; background: #f5f5f7;
    padding: 2px 8px; border-radius: 6px;
  }
  &__bottom { display: flex; align-items: flex-end; justify-content: space-between; gap: 8px; }
  &__price {
    white-space: nowrap;
    .price-symbol { font-size: 13px; color: #ff3b30; font-weight: 500; }
    .price-value { font-size: 20px; color: #ff3b30; font-weight: 700; letter-spacing: -0.02em; }
  }
  &__sku {
    overflow: hidden; color: #a1a1a6; font-size: 10px;
    text-overflow: ellipsis; white-space: nowrap;
  }
}
</style>
