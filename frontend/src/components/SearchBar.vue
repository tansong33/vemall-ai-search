<template>
  <div class="search-bar">
    <div class="search-bar__inner">
      <div class="search-bar__icon">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/>
        </svg>
      </div>
      <input
        ref="inputRef"
        :value="value"
        class="search-bar__input"
        type="text"
        placeholder="搜索商品，如：公牛插座、华为手机 256G..."
        @input="emit('input', $event.target.value)"
        @keyup.enter="emit('search', value)"
      />
      <button v-if="value" class="search-bar__clear" @click="handleClear">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <circle cx="12" cy="12" r="10"/><path d="m15 9-6 6"/><path d="m9 9 6 6"/>
        </svg>
      </button>
      <button class="search-bar__btn" @click="emit('search', value)">搜索</button>
    </div>
    <div class="search-bar__tags">
      <span class="search-bar__tags-label">热搜：</span>
      <span v-for="tag in hotTags" :key="tag" class="search-bar__tag" @click="handleTagClick(tag)">{{ tag }}</span>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'

defineProps({ value: { type: String, default: '' } })
const emit = defineEmits(['input', 'search'])
const inputRef = ref(null)

const hotTags = ['公牛插座', '南孚电池', '华为手机', '保温杯', '充电宝', '抽纸', '耳机', '雨伞']

function handleClear() {
  emit('input', '')
  inputRef.value?.focus()
}
function handleTagClick(tag) {
  emit('input', tag)
  emit('search', tag)
}
</script>

<style lang="scss" scoped>
.search-bar {
  padding: 24px 0 16px;

  &__inner {
    display: flex;
    align-items: center;
    background: rgba(255, 255, 255, 0.85);
    backdrop-filter: blur(20px);
    -webkit-backdrop-filter: blur(20px);
    border: 1px solid rgba(0, 0, 0, 0.08);
    border-radius: 14px;
    padding: 4px 4px 4px 16px;
    transition: all 0.3s cubic-bezier(0.25, 0.1, 0.25, 1);
    box-shadow: 0 1px 3px rgba(0, 0, 0, 0.04);

    &:focus-within {
      border-color: rgba(0, 122, 255, 0.4);
      box-shadow: 0 0 0 4px rgba(0, 122, 255, 0.08), 0 4px 16px rgba(0, 0, 0, 0.06);
      background: rgba(255, 255, 255, 0.95);
    }
  }

  &__icon { color: #86868b; flex-shrink: 0; display: flex; align-items: center; }

  &__input {
    flex: 1; border: none; outline: none; background: transparent;
    font-size: 16px; padding: 12px; color: #1d1d1f; font-family: inherit;
    &::placeholder { color: #86868b; }
  }

  &__clear {
    display: flex; align-items: center; justify-content: center;
    width: 28px; height: 28px; border-radius: 50%; border: none;
    background: rgba(0, 0, 0, 0.06); color: #86868b; cursor: pointer;
    flex-shrink: 0; transition: background 0.2s;
    &:hover { background: rgba(0, 0, 0, 0.1); }
  }

  &__btn {
    flex-shrink: 0; padding: 10px 24px; border: none; border-radius: 10px;
    background: #0071e3; color: white; font-size: 15px; font-weight: 500;
    cursor: pointer; transition: all 0.2s; font-family: inherit;
    &:hover { background: #0077ed; }
    &:active { transform: scale(0.97); }
  }

  &__tags {
    display: flex; align-items: center; gap: 8px;
    margin-top: 12px; padding: 0 4px; flex-wrap: wrap;
  }
  &__tags-label { font-size: 13px; color: #86868b; }
  &__tag {
    font-size: 13px; color: #1d1d1f; background: rgba(0, 0, 0, 0.04);
    padding: 4px 12px; border-radius: 20px; cursor: pointer; transition: all 0.2s;
    &:hover { background: rgba(0, 122, 255, 0.08); color: #0071e3; }
  }
}
</style>
