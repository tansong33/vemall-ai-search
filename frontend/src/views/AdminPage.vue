<template>
  <div class="admin-page">
    <header class="page-header">
      <p class="page-header__eyebrow">Administration</p>
      <h1>管理后台</h1>
      <p>NER 词典管理、版本发布与系统状态</p>
    </header>

    <div v-if="errorMessage" class="admin-page__error" role="alert">
      <span>{{ errorMessage }}</span>
      <button type="button" @click="reloadAll">重新加载</button>
    </div>

    <div class="stats-grid">
      <article class="stat-card">
        <div class="stat-card__label">NER 词典</div>
        <div class="stat-card__value">{{ formatNumber(stats.totalEntries) }}</div>
        <div class="stat-card__sub">词条总数</div>
      </article>
      <article class="stat-card">
        <div class="stat-card__label">当前版本</div>
        <div class="stat-card__value stat-card__value--text">{{ stats.currentVersion || '—' }}</div>
        <div class="stat-card__sub">{{ stats.entryCount ? formatNumber(stats.entryCount) + ' 条' : '等待发布' }}</div>
      </article>
      <article class="stat-card">
        <div class="stat-card__label">ES 文档数</div>
        <div class="stat-card__value">{{ formatOptionalNumber(syncStatus.esDocCount) }}</div>
        <div class="stat-card__sub">
          <span class="badge" :class="healthBadgeClass">{{ syncStatus.esClusterHealth || '未知' }}</span>
        </div>
      </article>
      <article class="stat-card">
        <div class="stat-card__label">DLQ</div>
        <div class="stat-card__value">{{ formatNumber(syncStatus.dlqSize || 0) }}</div>
        <div class="stat-card__sub">死信队列</div>
      </article>
    </div>

    <section class="section">
      <div class="section__header">
        <div>
          <p class="section__eyebrow">Dictionary</p>
          <h2>NER 词典管理</h2>
        </div>
        <div class="section__actions">
          <button type="button" class="btn" @click="openCreateDialog">+ 新增词条</button>
          <button type="button" class="btn btn--primary" :disabled="publishing" @click="handlePublish">
            {{ publishing ? '发布中...' : '发布新版本' }}
          </button>
        </div>
      </div>

      <form class="filter-bar" @submit.prevent="resetAndLoadEntries">
        <input v-model.trim="filters.term" placeholder="搜索词条..." class="filter-input" />
        <select v-model="filters.entityType" class="filter-select" @change="resetAndLoadEntries">
          <option value="">全部类型</option>
          <option v-for="option in entityTypeOptions" :key="option.value" :value="option.value">
            {{ option.label }}
          </option>
        </select>
        <select v-model="filters.status" class="filter-select" @change="resetAndLoadEntries">
          <option value="">全部状态</option>
          <option value="DRAFT">草稿</option>
          <option value="PUBLISHED">已发布</option>
          <option value="DISABLED">已禁用</option>
        </select>
        <button type="submit" class="btn">查询</button>
      </form>

      <div class="table-card">
        <div class="table-scroll">
          <table class="table">
            <thead>
              <tr>
                <th>词条</th>
                <th>类型</th>
                <th>来源</th>
                <th>状态</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="entry in entries" :key="entry.id">
                <td class="td-term">{{ entry.term }}</td>
                <td>
                  <span class="badge" :class="entityBadgeClass(entry.entityType)">
                    {{ entityLabel(entry.entityType) }}
                  </span>
                </td>
                <td><span class="code">{{ entry.sourceType || '—' }}</span></td>
                <td>
                  <span class="badge" :class="statusBadgeClass(entry.status)">
                    {{ statusLabel(entry.status) }}
                  </span>
                </td>
                <td>
                  <div class="td-actions">
                    <button
                      v-if="entry.status !== 'DISABLED'"
                      type="button"
                      class="btn btn--small"
                      @click="handleDisable(entry.id)"
                    >
                      禁用
                    </button>
                    <button
                      type="button"
                      class="btn btn--small btn--danger"
                      @click="handleDelete(entry.id)"
                    >
                      删除
                    </button>
                  </div>
                </td>
              </tr>
              <tr v-if="loading">
                <td colspan="5" class="td-empty">正在加载词典...</td>
              </tr>
              <tr v-else-if="!entries.length">
                <td colspan="5" class="td-empty">暂无数据</td>
              </tr>
            </tbody>
          </table>
        </div>

        <div v-if="total > pageSize" class="pagination">
          <button type="button" class="btn btn--small" :disabled="page <= 1 || loading" @click="goToPage(page - 1)">
            上一页
          </button>
          <span class="pagination__info">{{ page }} / {{ totalPages }}</span>
          <button type="button" class="btn btn--small" :disabled="page >= totalPages || loading" @click="goToPage(page + 1)">
            下一页
          </button>
          <span class="pagination__total">共 {{ formatNumber(total) }} 条</span>
        </div>
      </div>
    </section>

    <section class="section">
      <div class="section__header">
        <div>
          <p class="section__eyebrow">Runtime</p>
          <h2>系统配置</h2>
        </div>
      </div>
      <div class="config-grid">
        <article class="card">
          <h3 class="card__title">搜索参数</h3>
          <dl class="config-list">
            <div><dt>ES 索引</dt><dd class="code">products-read</dd></div>
            <div><dt>分页大小</dt><dd class="code">20 条/页</dd></div>
            <div><dt>NER 模式</dt><dd class="code">hybrid</dd></div>
            <div><dt>min_score</dt><dd class="code">1.0</dd></div>
          </dl>
        </article>
        <article class="card">
          <h3 class="card__title">服务状态</h3>
          <dl class="config-list">
            <div>
              <dt>Elasticsearch</dt>
              <dd><span class="badge" :class="healthBadgeClass">{{ syncStatus.esClusterHealth || '未知' }}</span></dd>
            </div>
            <div><dt>对账时间</dt><dd class="code">{{ syncStatus.lastReconcileAt || '—' }}</dd></div>
            <div><dt>索引版本</dt><dd class="code">v{{ syncStatus.indexVersion || '—' }}</dd></div>
          </dl>
        </article>
      </div>
    </section>

    <section class="section">
      <div class="section__header">
        <div>
          <p class="section__eyebrow">Redis</p>
          <h2>缓存管理</h2>
        </div>
        <div class="section__actions">
          <label class="btn" :class="{ 'btn--disabled': importing }">
            {{ importing ? '导入中...' : '导入 BIO 文件' }}
            <input type="file" accept=".txt,.bio" :disabled="importing" @change="handleImport" />
          </label>
          <button type="button" class="btn" @click="handleRefresh()">刷新全部</button>
          <button type="button" class="btn btn--danger" @click="handleInvalidate()">清除缓存</button>
        </div>
      </div>

      <div class="cache-stats-grid">
        <article class="stat-card">
          <div class="stat-card__label">缓存类型</div>
          <div class="stat-card__value">{{ cacheStats.totalTypes || 0 }}</div>
          <div class="stat-card__sub">entityType 数</div>
        </article>
        <article class="stat-card">
          <div class="stat-card__label">缓存词条</div>
          <div class="stat-card__value">{{ formatNumber(cacheStats.totalEntries || 0) }}</div>
          <div class="stat-card__sub">Redis Hash 总字段数</div>
        </article>
        <article class="stat-card">
          <div class="stat-card__label">当前版本</div>
          <div class="stat-card__value stat-card__value--text">{{ cacheStats.currentVersion || '—' }}</div>
          <div class="stat-card__sub">ner:dict:version</div>
        </article>
      </div>

      <div v-if="cacheTypes.length" class="table-card cache-table">
        <div class="table-scroll">
          <table class="table">
            <thead>
              <tr><th>实体类型</th><th>缓存大小</th><th>TTL（剩余）</th><th>操作</th></tr>
            </thead>
            <tbody>
              <tr v-for="item in cacheTypes" :key="item.type">
                <td><span class="badge badge--info">{{ item.type }}</span></td>
                <td class="code-cell">{{ formatNumber(item.size) }} 词</td>
                <td class="code-cell">{{ formatTtl(item.ttlSeconds) }}</td>
                <td>
                  <button type="button" class="btn btn--small" @click="handleRefresh(item.type)">刷新</button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
      <div v-else class="empty-card">暂无缓存数据，请导入 BIO 文件或刷新缓存</div>

      <article class="card lookup-card">
        <h3 class="card__title">词典查询（调试）</h3>
        <form class="lookup-form" @submit.prevent="handleLookup">
          <input v-model.trim="lookupWord" placeholder="输入实体词，如：华为" class="filter-input" />
          <select v-model="lookupEntityType" class="filter-select">
            <option v-for="option in lookupTypeOptions" :key="option.value" :value="option.value">
              {{ option.label }}
            </option>
          </select>
          <button type="submit" class="btn btn--primary">查询</button>
          <span
            v-if="lookupResult"
            class="lookup-result"
            :class="{ 'lookup-result--found': lookupResult.found }"
          >
            {{ lookupResult.found ? '✓ 存在' : '✗ 不存在' }}
          </span>
        </form>
      </article>
    </section>

    <div v-if="showCreateDialog" class="modal-overlay" @click.self="closeCreateDialog">
      <section class="modal" role="dialog" aria-modal="true" aria-labelledby="create-entry-title">
        <h2 id="create-entry-title">新增词典条目</h2>
        <label class="form-group">
          <span>词条</span>
          <input ref="entryTermInput" v-model.trim="newEntry.term" placeholder="如：华为" class="form-input" />
        </label>
        <label class="form-group">
          <span>类型</span>
          <select v-model="newEntry.entityType" class="form-input">
            <option v-for="option in entityTypeOptions" :key="option.value" :value="option.value">
              {{ option.label }}
            </option>
          </select>
        </label>
        <label class="form-group">
          <span>标准值（可选）</span>
          <input v-model.trim="newEntry.canonicalValue" placeholder="归一化后的标准值" class="form-input" />
        </label>
        <div class="modal-actions">
          <button type="button" class="btn" @click="closeCreateDialog">取消</button>
          <button
            type="button"
            class="btn btn--primary"
            :disabled="!newEntry.term || !newEntry.entityType || creating"
            @click="handleCreate"
          >
            {{ creating ? '创建中...' : '创建' }}
          </button>
        </div>
      </section>
    </div>
  </div>
</template>

<script>
import {
  createEntry,
  deleteEntry,
  disableEntry,
  getCacheStats,
  getSyncStatus,
  importBioFile,
  invalidateCache,
  listEntries,
  lookupDict,
  publishVersion,
  refreshCache
} from '@/api/admin'

const ENTITY_TYPE_OPTIONS = Object.freeze([
  { value: 'BRAND', label: '品牌' },
  { value: 'CATEGORY', label: '品类' },
  { value: 'MODEL', label: '型号' },
  { value: 'MODIFIER', label: '修饰词' },
  { value: 'ATTRIBUTE', label: '属性' }
])

const LOOKUP_TYPE_OPTIONS = Object.freeze([
  { value: 'BRAND', label: '品牌' },
  { value: 'CATEGORY', label: '品类' },
  { value: 'MODEL', label: '型号' },
  { value: 'SPEC', label: '规格' },
  { value: 'SERIES', label: '系列' },
  { value: 'SCENE', label: '场景' }
])

export default {
  name: 'AdminPage',
  data() {
    return {
      entityTypeOptions: ENTITY_TYPE_OPTIONS,
      lookupTypeOptions: LOOKUP_TYPE_OPTIONS,
      entries: [],
      total: 0,
      page: 1,
      pageSize: 20,
      loading: false,
      publishing: false,
      creating: false,
      importing: false,
      errorMessage: '',
      filters: { term: '', entityType: '', status: '' },
      stats: { totalEntries: 0, currentVersion: '', entryCount: 0 },
      syncStatus: {},
      cacheStats: { totalTypes: 0, totalEntries: 0, types: {} },
      showCreateDialog: false,
      newEntry: { term: '', entityType: 'BRAND', canonicalValue: '' },
      lookupWord: '',
      lookupEntityType: 'BRAND',
      lookupResult: null
    }
  },
  computed: {
    totalPages() {
      return Math.max(1, Math.ceil(this.total / this.pageSize))
    },
    healthBadgeClass() {
      return this.syncStatus.esClusterHealth === 'GREEN' ? 'badge--ok' : 'badge--warn'
    },
    cacheTypes() {
      const types = this.cacheStats && this.cacheStats.types
      if (!types || typeof types !== 'object') return []
      return Object.keys(types).map(type => ({
        type,
        size: types[type].size || 0,
        ttlSeconds: types[type].ttlSeconds
      }))
    }
  },
  mounted() {
    this.reloadAll()
  },
  methods: {
    async reloadAll() {
      this.errorMessage = ''
      await Promise.all([
        this.loadEntries(),
        this.loadSyncStatus(),
        this.loadCacheStats()
      ])
    },
    async loadEntries() {
      this.loading = true
      try {
        const params = { page: this.page, pageSize: this.pageSize }
        if (this.filters.term) params.term = this.filters.term
        if (this.filters.entityType) params.entityType = this.filters.entityType
        if (this.filters.status) params.status = this.filters.status
        const data = await listEntries(params)
        this.entries = data && Array.isArray(data.items) ? data.items : []
        this.total = data && data.total ? data.total : 0
        this.stats.totalEntries = this.total
      } catch (error) {
        this.setLoadError(error)
      } finally {
        this.loading = false
      }
    },
    async loadSyncStatus() {
      try {
        this.syncStatus = await getSyncStatus() || {}
      } catch (error) {
        this.setLoadError(error)
      }
    },
    async loadCacheStats() {
      try {
        this.cacheStats = await getCacheStats() || { totalTypes: 0, totalEntries: 0, types: {} }
      } catch (error) {
        this.setLoadError(error)
      }
    },
    setLoadError(error) {
      if (!this.errorMessage) {
        this.errorMessage = `管理服务加载失败：${error.message || '请检查服务是否可用'}`
      }
    },
    resetAndLoadEntries() {
      this.page = 1
      this.loadEntries()
    },
    goToPage(page) {
      if (page < 1 || page > this.totalPages || page === this.page) return
      this.page = page
      this.loadEntries()
    },
    openCreateDialog() {
      this.showCreateDialog = true
      this.$nextTick(() => {
        if (this.$refs.entryTermInput) this.$refs.entryTermInput.focus()
      })
    },
    closeCreateDialog() {
      if (this.creating) return
      this.showCreateDialog = false
      this.newEntry = { term: '', entityType: 'BRAND', canonicalValue: '' }
    },
    async handleCreate() {
      if (!this.newEntry.term || !this.newEntry.entityType || this.creating) return
      this.creating = true
      try {
        await createEntry({
          term: this.newEntry.term,
          entityType: this.newEntry.entityType,
          canonicalValue: this.newEntry.canonicalValue || undefined
        })
        this.showCreateDialog = false
        this.newEntry = { term: '', entityType: 'BRAND', canonicalValue: '' }
        await this.loadEntries()
      } catch (error) {
        window.alert('创建失败：' + error.message)
      } finally {
        this.creating = false
      }
    },
    async handleDisable(id) {
      if (!window.confirm('确定禁用该词条？')) return
      try {
        await disableEntry(id)
        await this.loadEntries()
      } catch (error) {
        window.alert('操作失败：' + error.message)
      }
    },
    async handleDelete(id) {
      if (!window.confirm('确定删除该词条？此操作不可撤销。')) return
      try {
        await deleteEntry(id)
        await this.loadEntries()
      } catch (error) {
        window.alert('删除失败：' + error.message)
      }
    },
    async handlePublish() {
      if (!window.confirm('确定发布新版本？')) return
      this.publishing = true
      try {
        const version = await publishVersion()
        this.stats.currentVersion = version.versionNo
        this.stats.entryCount = version.entryCount
        window.alert('发布成功：' + version.versionNo)
      } catch (error) {
        window.alert('发布失败：' + error.message)
      } finally {
        this.publishing = false
      }
    },
    async handleImport(event) {
      const file = event.target.files && event.target.files[0]
      if (!file) return
      this.importing = true
      try {
        const result = await importBioFile(file)
        window.alert(`导入成功：${result.totalInserted || 0} 条`)
        await Promise.all([this.loadCacheStats(), this.loadEntries()])
      } catch (error) {
        window.alert('导入失败：' + error.message)
      } finally {
        this.importing = false
        event.target.value = ''
      }
    },
    async handleRefresh(entityType) {
      try {
        await refreshCache(entityType)
        await this.loadCacheStats()
      } catch (error) {
        window.alert('刷新失败：' + error.message)
      }
    },
    async handleInvalidate() {
      if (!window.confirm('确定清除 NER 词典缓存？')) return
      try {
        await invalidateCache()
        await this.loadCacheStats()
      } catch (error) {
        window.alert('清除失败：' + error.message)
      }
    },
    async handleLookup() {
      if (!this.lookupWord) return
      try {
        this.lookupResult = await lookupDict(this.lookupWord, this.lookupEntityType)
      } catch (error) {
        window.alert('查询失败：' + error.message)
      }
    },
    entityLabel(type) {
      const option = ENTITY_TYPE_OPTIONS.find(item => item.value === type)
      return option ? option.label : type
    },
    entityBadgeClass(type) {
      return {
        BRAND: 'badge--info',
        CATEGORY: 'badge--ok',
        MODEL: 'badge--info',
        MODIFIER: 'badge--warn',
        ATTRIBUTE: 'badge--warn'
      }[type] || ''
    },
    statusLabel(status) {
      return { DRAFT: '草稿', PUBLISHED: '已发布', DISABLED: '已禁用' }[status] || status
    },
    statusBadgeClass(status) {
      return { DRAFT: 'badge--warn', PUBLISHED: 'badge--ok', DISABLED: 'badge--error' }[status] || ''
    },
    formatNumber(value) {
      return Number(value || 0).toLocaleString()
    },
    formatOptionalNumber(value) {
      return value === null || value === undefined ? '—' : Number(value).toLocaleString()
    },
    formatTtl(seconds) {
      if (seconds === null || seconds === undefined || seconds < 0) return '—'
      if (seconds < 60) return seconds + 's'
      if (seconds < 3600) return Math.round(seconds / 60) + 'min'
      if (seconds < 86400) return Math.round(seconds / 3600) + 'h'
      return Math.round(seconds / 86400) + 'd'
    }
  }
}
</script>

<style lang="scss" scoped>
.admin-page {
  max-width: 1200px;
  margin: 0 auto;
  padding: 32px 24px 72px;

  &__error {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    margin-bottom: 20px;
    padding: 11px 14px;
    border: 1px solid #f0b0d0;
    border-radius: 8px;
    background: #fff5f8;
    color: #c0265a;
    font-size: 13px;

    button {
      flex-shrink: 0;
      border: 0;
      background: transparent;
      color: inherit;
      cursor: pointer;
      font-weight: 600;
    }
  }
}

.page-header {
  margin-bottom: 32px;
  &__eyebrow {
    margin-bottom: 12px;
    color: #8f8f8f;
    font-family: Consolas, 'SFMono-Regular', monospace;
    font-size: 12px;
    font-weight: 500;
    letter-spacing: 0.5px;
    text-transform: uppercase;
  }
  h1 { margin-bottom: 8px; font-size: 32px; font-weight: 600; letter-spacing: -1.28px; line-height: 40px; }
  > p:last-child { color: #4d4d4d; font-size: 16px; line-height: 24px; }
}

.stats-grid,
.cache-stats-grid {
  display: grid;
  gap: 16px;
}
.stats-grid { grid-template-columns: repeat(4, minmax(0, 1fr)); margin-bottom: 32px; }
.cache-stats-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); }

.stat-card,
.card,
.table-card,
.empty-card {
  border: 1px solid #ebebeb;
  border-radius: 12px;
  background: #fff;
  box-shadow: 0 1px 1px rgba(0, 0, 0, 0.04);
}

.stat-card {
  min-width: 0;
  padding: 20px;
  &__label { margin-bottom: 8px; color: #8f8f8f; font-size: 12px; font-weight: 500; }
  &__value {
    overflow: hidden;
    margin-bottom: 4px;
    font-size: 28px;
    font-weight: 600;
    letter-spacing: -1px;
    text-overflow: ellipsis;
    white-space: nowrap;
    &--text { font-size: 18px; letter-spacing: -0.3px; }
  }
  &__sub { min-height: 18px; color: #4d4d4d; font-size: 12px; }
}

.section {
  margin-bottom: 36px;
  &__header {
    display: flex;
    align-items: flex-end;
    justify-content: space-between;
    gap: 16px;
    margin-bottom: 16px;
    h2 { font-size: 20px; font-weight: 600; letter-spacing: -0.4px; }
  }
  &__eyebrow {
    margin-bottom: 5px;
    color: #8f8f8f;
    font-family: Consolas, 'SFMono-Regular', monospace;
    font-size: 10px;
    font-weight: 600;
    letter-spacing: 0.6px;
    text-transform: uppercase;
  }
  &__actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 8px; }
}

.filter-bar,
.lookup-form {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
.filter-bar { margin-bottom: 16px; }
.filter-input,
.filter-select,
.form-input {
  border: 1px solid #ebebeb;
  border-radius: 6px;
  outline: none;
  background: #fff;
  color: #171717;
  font-family: inherit;
  font-size: 14px;
  &:focus { border-color: #0070f3; box-shadow: 0 0 0 1px #0070f3; }
}
.filter-input,
.filter-select { padding: 8px 12px; }
.filter-input { min-width: 200px; }

.btn {
  padding: 8px 16px;
  border: 1px solid #ebebeb;
  border-radius: 100px;
  background: #fff;
  color: #4d4d4d;
  cursor: pointer;
  font-family: inherit;
  font-size: 13px;
  font-weight: 500;
  transition: border-color 0.15s, color 0.15s, opacity 0.15s, transform 0.15s;
  &:hover:not(:disabled) { border-color: #171717; color: #171717; }
  &:active:not(:disabled) { transform: scale(0.97); }
  &:disabled,
  &--disabled { cursor: not-allowed; opacity: 0.5; }
  &--primary {
    border-color: #171717;
    background: #171717;
    color: #fff;
    &:hover:not(:disabled) { color: #fff; opacity: 0.86; }
  }
  &--small { padding: 4px 10px; font-size: 12px; }
  &--danger {
    border-color: #ffc0cb;
    color: #ee0000;
    &:hover:not(:disabled) { border-color: #ee0000; background: #fff5f5; color: #ee0000; }
  }
  input[type='file'] { display: none; }
}

.table-card { overflow: hidden; }
.table-scroll { overflow-x: auto; }
.table {
  width: 100%;
  border-collapse: collapse;
  th {
    padding: 12px 16px;
    border-bottom: 1px solid #ebebeb;
    color: #8f8f8f;
    font-size: 12px;
    font-weight: 600;
    letter-spacing: 0.5px;
    text-align: left;
    text-transform: uppercase;
    white-space: nowrap;
  }
  td {
    padding: 14px 16px;
    border-bottom: 1px solid #f2f2f2;
    font-size: 14px;
  }
  tr:last-child td { border-bottom: 0; }
  tbody tr:hover td { background: #f9f9f9; }
}
.td-term { min-width: 160px; font-weight: 500; }
.td-actions { display: flex; gap: 6px; }
.td-empty { padding: 40px 16px !important; color: #8f8f8f; text-align: center; }
.code,
.code-cell {
  font-family: Consolas, 'SFMono-Regular', monospace;
  font-size: 12px;
}
.code {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  padding: 2px 8px;
  border: 1px solid #ebebeb;
  border-radius: 8px;
  background: #fafafa;
  text-overflow: ellipsis;
  vertical-align: middle;
  white-space: nowrap;
}

.badge {
  display: inline-block;
  padding: 3px 8px;
  border-radius: 100px;
  font-size: 11px;
  font-weight: 600;
  &--ok { background: #e6f7ed; color: #00a854; }
  &--warn { background: #ffefcf; color: #ab570a; }
  &--error { background: #ffe0ec; color: #ee0000; }
  &--info { background: #d3e5ff; color: #0761d1; }
}

.pagination {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  border-top: 1px solid #ebebeb;
  &__info { color: #4d4d4d; font-size: 13px; }
  &__total { margin-left: auto; color: #8f8f8f; font-size: 12px; }
}

.config-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.card { padding: 24px; }
.card__title { margin-bottom: 14px; color: #4d4d4d; font-size: 13px; font-weight: 600; }
.config-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  > div { display: flex; align-items: center; justify-content: space-between; gap: 12px; min-width: 0; }
  dt { color: #8f8f8f; font-size: 13px; }
  dd { min-width: 0; color: #171717; font-size: 13px; }
}

.cache-table { margin-top: 12px; }
.empty-card { margin-top: 12px; padding: 24px; color: #8f8f8f; font-size: 13px; text-align: center; }
.lookup-card { margin-top: 16px; }
.lookup-result {
  color: #ee0000;
  font-family: Consolas, 'SFMono-Regular', monospace;
  font-size: 12px;
  font-weight: 600;
  &--found { color: #00a854; }
}

.modal-overlay {
  position: fixed;
  inset: 0;
  z-index: 1000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  background: rgba(0, 0, 0, 0.42);
  backdrop-filter: blur(4px);
}
.modal {
  width: 480px;
  max-width: 100%;
  padding: 32px;
  border-radius: 16px;
  background: #fff;
  box-shadow: 0 24px 70px rgba(0, 0, 0, 0.2);
  h2 { margin-bottom: 24px; font-size: 20px; font-weight: 600; }
}
.form-group {
  display: block;
  margin-bottom: 16px;
  > span { display: block; margin-bottom: 6px; color: #4d4d4d; font-size: 13px; font-weight: 500; }
}
.form-input { width: 100%; padding: 10px 12px; }
.modal-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 24px; }

@media (max-width: 900px) {
  .stats-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}

@media (max-width: 680px) {
  .admin-page { padding: 24px 16px 48px; }
  .page-header { margin-bottom: 24px; }
  .section {
    &__header { align-items: flex-start; flex-direction: column; }
    &__actions { justify-content: flex-start; }
  }
  .config-grid,
  .cache-stats-grid { grid-template-columns: 1fr; }
  .filter-input { min-width: 0; flex: 1 1 100%; }
}

@media (max-width: 440px) {
  .stats-grid { grid-template-columns: 1fr; }
  .admin-page__error { align-items: flex-start; flex-direction: column; }
  .pagination { gap: 8px; }
  .pagination__total { display: none; }
}
</style>
