<template>
  <div class="pipeline">
    <div class="pipeline__header">
      <h3 class="pipeline__title">搜索 Pipeline</h3>
      <span class="pipeline__cost" v-if="data.totalCostMs">总耗时 {{ data.totalCostMs }}ms</span>
    </div>
    <div class="pipeline__steps">
      <!-- ① NER -->
      <div class="pipeline__step" :class="{ 'pipeline__step--active': data.nerResult }">
        <div class="step__badge">①</div>
        <div class="step__content">
          <div class="step__name">NER 识别</div>
          <div class="step__time" v-if="data.nerResult">{{ data.nerResult.costMs }}ms</div>
          <div class="step__body" v-if="data.nerResult && data.nerResult.entities">
            <div v-if="data.nerResult.entities.length === 0" class="step__empty">未识别到实体</div>
            <div v-else class="entity-tags">
              <span
                v-for="(e, i) in data.nerResult.entities"
                :key="i"
                class="entity-tag"
                :class="'entity-tag--' + (e.label || e.entityType || 'other').toLowerCase()"
              >
                {{ e.text || e.word }}
                <em>{{ e.label || e.entityType }}</em>
              </span>
            </div>
          </div>
        </div>
      </div>
      <div class="pipeline__arrow">→</div>
      <!-- ② 模型处理 -->
      <div class="pipeline__step" :class="{ 'pipeline__step--active': data.modelResult }">
        <div class="step__badge">②</div>
        <div class="step__content">
          <div class="step__name">模型处理</div>
          <div class="step__time" v-if="data.modelResult">{{ data.modelResult.costMs }}ms</div>
          <div class="step__body" v-if="data.modelResult">
            <div class="step__kv" v-if="data.modelResult.rewrittenQuery">
              <span class="kv-label">重写</span>
              <span class="kv-value">{{ data.modelResult.rewrittenQuery }}</span>
            </div>
            <div class="step__kv" v-if="data.modelResult.synonyms && data.modelResult.synonyms.length">
              <span class="kv-label">同义词</span>
              <span class="kv-value">{{ data.modelResult.synonyms.join('、') }}</span>
            </div>
            <div v-if="!data.modelResult.rewrittenQuery && (!data.modelResult.synonyms || !data.modelResult.synonyms.length)" class="step__empty">直接透传</div>
          </div>
        </div>
      </div>
      <div class="pipeline__arrow">→</div>
      <!-- ③ ES 分词 -->
      <div class="pipeline__step" :class="{ 'pipeline__step--active': data.esAnalyzeResult }">
        <div class="step__badge">③</div>
        <div class="step__content">
          <div class="step__name">ES 分词</div>
          <div class="step__time" v-if="data.esAnalyzeResult">{{ data.esAnalyzeResult.costMs }}ms</div>
          <div class="step__body" v-if="data.esAnalyzeResult && data.esAnalyzeResult.tokens">
            <div class="token-list">
              <span v-for="(t, i) in data.esAnalyzeResult.tokens" :key="i" class="token">{{ t.term }}</span>
            </div>
          </div>
        </div>
      </div>
      <div class="pipeline__arrow">→</div>
      <!-- ④ 检索结果 -->
      <div class="pipeline__step pipeline__step--result" :class="{ 'pipeline__step--active': data.searchResult }">
        <div class="step__badge">④</div>
        <div class="step__content">
          <div class="step__name">ES 检索</div>
          <div class="step__time" v-if="data.searchResult">{{ data.searchResult.costMs }}ms</div>
          <div class="step__body" v-if="data.searchResult">
            <div class="result-summary">命中 <strong>{{ data.searchResult.total }}</strong> 条</div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
defineProps({ data: { type: Object, required: true } })
</script>

<style lang="scss" scoped>
.pipeline {
  background: rgba(255, 255, 255, 0.7);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  border: 1px solid rgba(0, 0, 0, 0.06);
  border-radius: 16px;
  padding: 20px 24px;
  margin-bottom: 20px;

  &__header {
    display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px;
  }
  &__title { font-size: 15px; font-weight: 600; color: #1d1d1f; letter-spacing: -0.01em; }
  &__cost {
    font-size: 12px; color: #86868b; background: rgba(0, 0, 0, 0.04);
    padding: 3px 10px; border-radius: 12px;
  }
  &__steps { display: flex; align-items: stretch; gap: 0; overflow-x: auto; }
  &__step {
    flex: 1; min-width: 160px;
    background: rgba(255, 255, 255, 0.6);
    border: 1px solid rgba(0, 0, 0, 0.06);
    border-radius: 12px; padding: 14px;
    transition: all 0.3s;
    &--active { border-color: rgba(0, 122, 255, 0.15); background: rgba(0, 122, 255, 0.02); }
    &--result .step__badge { background: #34c759; }
  }
  &__arrow {
    display: flex; align-items: center; padding: 0 6px;
    color: #c7c7cc; font-size: 18px; flex-shrink: 0;
  }
}
.step {
  &__badge {
    display: inline-flex; align-items: center; justify-content: center;
    width: 24px; height: 24px; border-radius: 8px;
    background: #0071e3; color: white; font-size: 12px; font-weight: 600; margin-bottom: 8px;
  }
  &__name { font-size: 13px; font-weight: 600; color: #1d1d1f; margin-bottom: 2px; }
  &__time { font-size: 11px; color: #86868b; margin-bottom: 8px; }
  &__body { margin-top: 6px; min-height: 28px; }
  &__empty { font-size: 12px; color: #86868b; }
  &__kv { display: flex; gap: 4px; margin-bottom: 4px; font-size: 12px; line-height: 1.5; }
}
.kv-label { color: #86868b; flex-shrink: 0; &::after { content: '：'; } }
.kv-value { color: #1d1d1f; word-break: break-all; }
.entity-tags { display: flex; flex-wrap: wrap; gap: 4px; }
.entity-tag {
  display: inline-flex; align-items: center; gap: 3px;
  padding: 2px 8px; border-radius: 6px; font-size: 12px; font-weight: 500;
  em { font-style: normal; font-size: 10px; opacity: 0.7; }
  &--brand { background: #fef2f2; color: #dc2626; }
  &--category { background: #f0fdf4; color: #16a34a; }
  &--attribute { background: #fefce8; color: #ca8a04; }
  &--modifier { background: #f5f3ff; color: #7c3aed; }
  &--other { background: #f4f4f5; color: #71717a; }
}
.token-list { display: flex; flex-wrap: wrap; gap: 4px; }
.token {
  display: inline-block; padding: 2px 8px;
  background: rgba(0, 122, 255, 0.06); color: #0071e3;
  border-radius: 6px; font-size: 12px; font-weight: 500;
}
.result-summary {
  font-size: 13px; color: #1d1d1f;
  strong { color: #0071e3; font-size: 18px; }
}
</style>
