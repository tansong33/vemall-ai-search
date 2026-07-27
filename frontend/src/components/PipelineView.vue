<template>
  <div class="debug-page">
    <SearchBar v-model="queryText" @search="runPipeline" />
    <div class="debug-page__intro">
      <div>
        <h1>搜索链路调试</h1>
        <p>查看实体识别、查询理解、Elasticsearch、缓存和耗时信息。</p>
      </div>
      <span class="debug-page__hint">本页每次搜索只调用一次调试接口</span>
    </div>

    <div v-if="errorMessage" class="debug-page__error">{{ errorMessage }}</div>
    <div v-if="loading" class="debug-page__loading">
      <div class="loading-spinner"></div>
      <span>正在执行完整搜索链路...</span>
    </div>

    <div v-if="pipelineData && !loading" class="pipeline">
      <div class="pipeline__header">
        <div>
          <h2>Pipeline 执行结果</h2>
          <p>查询词：{{ lastQuery }}</p>
        </div>
        <span class="pipeline__cost">总耗时 {{ timingStage.totalMs || 0 }} ms</span>
      </div>

      <div class="pipeline__grid">
        <section class="stage-card">
          <header class="stage-card__header">
            <span class="stage-card__badge">1</span>
            <div>
              <h3>NER 实体识别</h3>
              <span>{{ nerStage.costMs || 0 }} ms</span>
            </div>
          </header>
          <dl class="stage-meta">
            <div><dt>提供方</dt><dd>{{ nerStage.provider || '—' }}</dd></div>
            <div><dt>模式</dt><dd>{{ nerStage.mode || '—' }}</dd></div>
            <div><dt>模型</dt><dd>{{ nerStage.modelVersion || '—' }}</dd></div>
            <div><dt>状态</dt><dd>{{ nerStage.modelReady ? '已就绪' : '未就绪' }}</dd></div>
          </dl>
          <div v-if="nerEntities.length" class="entity-list">
            <div v-for="(entity, index) in nerEntities" :key="entity.start + '-' + entity.end + '-' + index" class="entity">
              <span class="entity__text">{{ entity.text }}</span>
              <span class="entity__label" :class="entityClass(entity.label)">{{ entity.label || 'OTHER' }}</span>
              <span v-if="entity.normalizedText" class="entity__normalized">→ {{ entity.normalizedText }}</span>
              <span v-if="entity.confidence !== null && entity.confidence !== undefined" class="entity__confidence">
                {{ formatConfidence(entity.confidence) }}
              </span>
            </div>
          </div>
          <p v-else class="stage-card__empty">未识别到实体</p>
        </section>

        <section class="stage-card">
          <header class="stage-card__header">
            <span class="stage-card__badge">2</span>
            <div>
              <h3>查询理解</h3>
              <span>{{ queryStage.costMs || 0 }} ms</span>
            </div>
          </header>
          <div class="field-block">
            <span class="field-block__label">重写查询</span>
            <strong>{{ queryStage.rewrittenQuery || lastQuery }}</strong>
          </div>
          <div class="field-block">
            <span class="field-block__label">分析词元</span>
            <div v-if="analyzedTokens.length" class="tag-list">
              <span v-for="token in analyzedTokens" :key="token" class="tag">{{ token }}</span>
            </div>
            <span v-else class="stage-card__empty">无</span>
          </div>
          <div class="field-block">
            <span class="field-block__label">同义词扩展</span>
            <div v-if="synonyms.length" class="tag-list">
              <span v-for="word in synonyms" :key="word" class="tag tag--green">{{ word }}</span>
            </div>
            <span v-else class="stage-card__empty">无</span>
          </div>
        </section>

        <section class="stage-card stage-card--wide">
          <header class="stage-card__header">
            <span class="stage-card__badge">3</span>
            <div>
              <h3>Elasticsearch 查询</h3>
              <span>{{ esStage.costMs || 0 }} ms · 命中 {{ esStage.totalHits || 0 }} 条</span>
            </div>
          </header>
          <div class="clause-columns">
            <div class="clause-block">
              <h4>必须条件</h4>
              <ul v-if="mustClauses.length">
                <li v-for="(clause, index) in mustClauses" :key="'must-' + index">{{ clause }}</li>
              </ul>
              <span v-else class="stage-card__empty">无</span>
            </div>
            <div class="clause-block">
              <h4>加权条件</h4>
              <ul v-if="shouldClauses.length">
                <li v-for="(clause, index) in shouldClauses" :key="'should-' + index">{{ clause }}</li>
              </ul>
              <span v-else class="stage-card__empty">无</span>
            </div>
            <div class="clause-block clause-block--exclusion">
              <h4>配件排除词</h4>
              <div v-if="mustNotClauses.length" class="tag-list">
                <span v-for="(clause, index) in mustNotClauses" :key="'exclude-' + index" class="tag tag--red">
                  {{ clause }}
                </span>
              </div>
              <span v-else class="stage-card__empty">本次查询未生成配件排除词</span>
            </div>
          </div>
          <details v-if="esStage.dsl" class="dsl">
            <summary>查看 Elasticsearch DSL</summary>
            <pre>{{ esStage.dsl }}</pre>
          </details>
        </section>

        <section class="stage-card">
          <header class="stage-card__header">
            <span class="stage-card__badge">4</span>
            <div>
              <h3>缓存</h3>
              <span>{{ cacheStage.lookupMs || 0 }} ms</span>
            </div>
          </header>
          <dl class="stage-meta stage-meta--vertical">
            <div><dt>状态</dt><dd>{{ cacheStage.status || '—' }}</dd></div>
            <div><dt>缓存键</dt><dd class="stage-meta__key">{{ cacheStage.key || '—' }}</dd></div>
          </dl>
        </section>

        <section class="stage-card">
          <header class="stage-card__header">
            <span class="stage-card__badge">5</span>
            <div>
              <h3>阶段耗时</h3>
              <span>总计 {{ timingStage.totalMs || 0 }} ms</span>
            </div>
          </header>
          <div class="timing-list">
            <div><span>缓存</span><strong>{{ timingStage.cacheMs || 0 }} ms</strong></div>
            <div><span>NER</span><strong>{{ timingStage.nerMs || 0 }} ms</strong></div>
            <div><span>查询理解</span><strong>{{ timingStage.queryMs || 0 }} ms</strong></div>
            <div><span>ES</span><strong>{{ timingStage.esMs || 0 }} ms</strong></div>
          </div>
        </section>

        <section class="stage-card stage-card--result stage-card--wide">
          <header class="stage-card__header">
            <span class="stage-card__badge">6</span>
            <div>
              <h3>搜索结果</h3>
              <span>
                第 {{ resultStage.page || 1 }} 页 · {{ resultStage.total || 0 }} 件商品
                <template v-if="resultStage.rawTotal > resultStage.total">
                  · 原始命中 {{ resultStage.rawTotal }} 条
                </template>
              </span>
            </div>
          </header>
          <div class="result-meta">
            <span>搜索耗时 {{ resultStage.tookMs || 0 }} ms</span>
            <span>缓存 {{ resultStage.cacheStatus || '—' }}</span>
            <span v-if="resultStage.degraded" class="result-meta__degraded">
              已降级：{{ degradeReasons.join('、') || '未知原因' }}
            </span>
          </div>
          <div v-if="resultItems.length" class="result-grid">
            <ProductCard
              v-for="product in resultItems"
              :key="product.skuId || product.spuId || product.title"
              :product="product"
            />
          </div>
          <p v-else class="stage-card__empty">没有搜索结果</p>
        </section>
      </div>
    </div>

    <div v-if="!pipelineData && !loading && !errorMessage" class="debug-page__welcome">
      输入搜索词后，可在一个响应中查看完整链路和商品结果。
    </div>
  </div>
</template>

<script>
import { debugPipeline } from '@/api/search'
import SearchBar from '@/components/SearchBar.vue'
import ProductCard from '@/components/ProductCard.vue'

const EMPTY_OBJECT = Object.freeze({})

export default {
  name: 'PipelineView',
  components: { SearchBar, ProductCard },
  data() {
    return {
      queryText: '',
      lastQuery: '',
      loading: false,
      errorMessage: '',
      pipelineData: null,
      requestSequence: 0
    }
  },
  computed: {
    nerStage() {
      return this.pipelineData && this.pipelineData.ner
        ? this.pipelineData.ner
        : EMPTY_OBJECT
    },
    queryStage() {
      return this.pipelineData && this.pipelineData.query
        ? this.pipelineData.query
        : EMPTY_OBJECT
    },
    esStage() {
      return this.pipelineData && this.pipelineData.es
        ? this.pipelineData.es
        : EMPTY_OBJECT
    },
    cacheStage() {
      return this.pipelineData && this.pipelineData.cache
        ? this.pipelineData.cache
        : EMPTY_OBJECT
    },
    timingStage() {
      return this.pipelineData && this.pipelineData.timing
        ? this.pipelineData.timing
        : EMPTY_OBJECT
    },
    resultStage() {
      return this.pipelineData && this.pipelineData.result
        ? this.pipelineData.result
        : EMPTY_OBJECT
    },
    nerEntities() {
      return Array.isArray(this.nerStage.entities) ? this.nerStage.entities : []
    },
    synonyms() {
      return Array.isArray(this.queryStage.synonyms) ? this.queryStage.synonyms : []
    },
    analyzedTokens() {
      return Array.isArray(this.queryStage.analyzedTokens) ? this.queryStage.analyzedTokens : []
    },
    mustClauses() {
      return Array.isArray(this.esStage.mustClauses) ? this.esStage.mustClauses : []
    },
    shouldClauses() {
      return Array.isArray(this.esStage.shouldClauses) ? this.esStage.shouldClauses : []
    },
    mustNotClauses() {
      return Array.isArray(this.esStage.mustNotClauses) ? this.esStage.mustNotClauses : []
    },
    resultItems() {
      return Array.isArray(this.resultStage.items) ? this.resultStage.items : []
    },
    degradeReasons() {
      return Array.isArray(this.resultStage.degradeReasons) ? this.resultStage.degradeReasons : []
    }
  },
  methods: {
    async runPipeline(selectedQuery) {
      if (typeof selectedQuery === 'string') {
        this.queryText = selectedQuery
      }
      const normalizedQuery = this.queryText.trim()
      if (!normalizedQuery) return

      const requestId = ++this.requestSequence
      this.loading = true
      this.errorMessage = ''
      this.pipelineData = null
      this.lastQuery = normalizedQuery
      try {
        const data = await debugPipeline({
          query: normalizedQuery,
          page: 1,
          pageSize: 20,
          sort: 'RELEVANCE',
          filters: {
            brands: [],
            categories: [],
            minPriceFen: null,
            maxPriceFen: null,
            inStock: null
          },
          includeEsDsl: true
        })
        if (requestId !== this.requestSequence) return
        if (!data) {
          throw new Error('调试接口未返回数据')
        }
        this.pipelineData = data
      } catch (error) {
        if (requestId !== this.requestSequence) return
        this.errorMessage = error.message || '调试请求失败'
      } finally {
        if (requestId === this.requestSequence) {
          this.loading = false
        }
      }
    },
    formatConfidence(value) {
      return (Number(value) * 100).toFixed(1) + '%'
    },
    entityClass(label) {
      const normalized = String(label || 'other')
        .toLowerCase()
        .replace(/^[bi]-/, '')
      return 'entity__label--' + normalized
    }
  }
}
</script>

<style lang="scss" scoped>
.debug-page {
  max-width: $page-max-width;
  min-height: calc(100vh - 60px);
  margin: 0 auto;
  padding: 0 24px 60px;

  &__intro {
    display: flex; justify-content: space-between; align-items: flex-end; gap: 20px;
    margin: 4px 0 20px;
    h1 { margin-bottom: 4px; color: #1d1d1f; font-size: 24px; }
    p { color: #86868b; font-size: 14px; }
  }
  &__hint {
    padding: 5px 10px; border-radius: 999px;
    background: #ecfdf3; color: #027a48; font-size: 12px; white-space: nowrap;
  }
  &__error {
    padding: 10px 16px; border: 1px solid #fecdca; border-radius: 10px;
    background: #fef3f2; color: #b42318; font-size: 13px;
  }
  &__loading {
    display: flex; justify-content: center; align-items: center;
    gap: 12px; padding: 80px 0; color: #86868b;
  }
  &__welcome {
    padding: 90px 20px; border: 1px dashed #d2d2d7; border-radius: 16px;
    color: #86868b; text-align: center; background: rgba(255, 255, 255, 0.45);
  }
}

.pipeline {
  &__header {
    display: flex; justify-content: space-between; align-items: center; gap: 16px;
    margin-bottom: 16px;
    h2 { color: #1d1d1f; font-size: 18px; }
    p { margin-top: 3px; color: #86868b; font-size: 13px; }
  }
  &__cost {
    padding: 5px 12px; border-radius: 999px;
    background: rgba(0, 122, 255, 0.08); color: #0071e3; font-size: 12px;
  }
  &__grid {
    display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px;
  }
}

.stage-card {
  min-width: 0; padding: 18px;
  border: 1px solid rgba(0, 0, 0, 0.06); border-radius: 14px;
  background: rgba(255, 255, 255, 0.82);

  &--wide { grid-column: 1 / -1; }
  &--result { border-color: rgba(52, 199, 89, 0.22); }
  &__header {
    display: flex; align-items: center; gap: 10px; margin-bottom: 14px;
    h3 { color: #1d1d1f; font-size: 15px; }
    span { color: #86868b; font-size: 11px; }
  }
  &__badge {
    display: flex; align-items: center; justify-content: center;
    width: 28px; height: 28px; flex-shrink: 0; border-radius: 9px;
    background: #0071e3; color: white !important; font-size: 12px !important; font-weight: 700;
  }
  &--result &__badge { background: #34c759; }
  &__empty { color: #a1a1a6; font-size: 12px; }
}

.stage-meta {
  display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px;
  margin-bottom: 12px;
  div { padding: 7px 9px; border-radius: 8px; background: #f5f5f7; }
  dt { margin-bottom: 2px; color: #86868b; font-size: 10px; }
  dd { overflow: hidden; color: #1d1d1f; font-size: 12px; text-overflow: ellipsis; }
  &--vertical { grid-template-columns: 1fr; }
  &__key { word-break: break-all; }
}

.entity-list { display: flex; flex-direction: column; gap: 6px; }
.entity {
  display: flex; align-items: center; gap: 6px; min-width: 0; font-size: 12px;
  &__text { color: #1d1d1f; font-weight: 600; }
  &__label {
    padding: 2px 6px; border-radius: 5px; background: #f4f4f5; color: #71717a; font-size: 10px;
    &--brand { background: #fef2f2; color: #dc2626; }
    &--category { background: #f0fdf4; color: #16a34a; }
    &--attribute { background: #fefce8; color: #ca8a04; }
    &--modifier { background: #f5f3ff; color: #7c3aed; }
  }
  &__normalized { overflow: hidden; color: #0071e3; text-overflow: ellipsis; white-space: nowrap; }
  &__confidence { margin-left: auto; color: #86868b; font-size: 10px; }
}

.field-block {
  margin-bottom: 12px;
  &__label { display: block; margin-bottom: 5px; color: #86868b; font-size: 11px; }
  strong { color: #1d1d1f; font-size: 13px; }
}
.tag-list { display: flex; flex-wrap: wrap; gap: 5px; }
.tag {
  display: inline-block; padding: 3px 8px; border-radius: 6px;
  background: #eef6ff; color: #175cd3; font-size: 11px; word-break: break-all;
  &--green { background: #ecfdf3; color: #027a48; }
  &--red { background: #fff1f0; color: #c4320a; font-weight: 600; }
}

.clause-columns {
  display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px;
}
.clause-block {
  min-width: 0; padding: 12px; border-radius: 10px; background: #f8f8fa;
  h4 { margin-bottom: 7px; color: #3a3a3c; font-size: 12px; }
  ul { padding-left: 18px; color: #636366; font-size: 11px; line-height: 1.6; }
  li { overflow-wrap: anywhere; }
  &--exclusion {
    grid-column: 1 / -1;
    border: 1px solid #fecaca; background: #fff7f7;
    h4 { color: #b42318; font-size: 13px; }
  }
}
.dsl {
  margin-top: 12px;
  summary { color: #0071e3; cursor: pointer; font-size: 12px; }
  pre {
    max-height: 360px; margin-top: 8px; padding: 12px; overflow: auto;
    border-radius: 9px; background: #1d1d1f; color: #f5f5f7;
    font-family: Consolas, Monaco, monospace; font-size: 11px; white-space: pre-wrap; word-break: break-all;
  }
}

.timing-list {
  display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px;
  div {
    display: flex; justify-content: space-between; gap: 8px;
    padding: 8px 10px; border-radius: 8px; background: #f5f5f7; font-size: 12px;
  }
  span { color: #86868b; }
  strong { color: #1d1d1f; }
}
.result-meta {
  display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 14px;
  span { padding: 4px 9px; border-radius: 7px; background: #f5f5f7; color: #636366; font-size: 11px; }
  &__degraded { background: #fefce8 !important; color: #854d0e !important; }
}
.result-grid {
  display: grid; grid-template-columns: repeat(auto-fill, minmax(210px, 1fr)); gap: 12px;
}
.loading-spinner {
  width: 24px; height: 24px;
  border: 2.5px solid rgba(0, 122, 255, 0.15);
  border-top-color: #0071e3; border-radius: 50%;
  animation: spin 0.8s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }

@media (max-width: 760px) {
  .debug-page {
    padding: 0 16px 40px;
    &__intro { align-items: flex-start; flex-direction: column; }
    &__hint { white-space: normal; }
  }
  .pipeline {
    &__header { align-items: flex-start; flex-direction: column; }
    &__grid { grid-template-columns: 1fr; }
  }
  .stage-card--wide { grid-column: auto; }
  .clause-columns { grid-template-columns: 1fr; }
  .clause-block--exclusion { grid-column: auto; }
}
</style>
