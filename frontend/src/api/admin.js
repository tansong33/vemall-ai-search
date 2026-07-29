import axios from 'axios'

const http = axios.create({
  baseURL: '/ai-search/v1/admin',
  timeout: 15000
})

http.interceptors.response.use(
  response => {
    const body = response.data
    if (body && typeof body === 'object' && 'success' in body) {
      if (body.success) return body.data
      return Promise.reject(new Error(body.message || body.code || '请求失败'))
    }
    return body
  },
  error => {
    const body = error.response && error.response.data
    const message = (body && (body.message || body.code)) || error.message || '请求失败'
    return Promise.reject(new Error(message))
  }
)

export function listEntries(params = {}) {
  return http.get('/dict/entries', { params })
}

export function createEntry(data) {
  return http.post('/dict/entries', data)
}

export function updateEntry(id, data) {
  return http.put(`/dict/entries/${id}`, data)
}

export function disableEntry(id) {
  return http.post(`/dict/entries/${id}/disable`)
}

export function deleteEntry(id) {
  return http.delete(`/dict/entries/${id}`)
}

export function publishVersion(operator = 'admin') {
  return http.post('/dict/versions', { operator })
}

export function rollbackVersion(version) {
  return http.post(`/dict/versions/${version}/rollback`)
}

export function listReviews(params = {}) {
  return http.get('/reviews', { params })
}

export function getReviewDetail(reviewId) {
  return http.get(`/reviews/${reviewId}`)
}

export function submitDecision(reviewId, data) {
  return http.post(`/reviews/${reviewId}/decision`, data)
}

export function getSyncStatus() {
  return http.get('/sync/status')
}

export function triggerReindex() {
  return http.post('/sync/reindex')
}

export function getJobProgress(jobId) {
  return http.get(`/sync/jobs/${jobId}`)
}

export function triggerReconcile() {
  return http.post('/sync/reconcile')
}

export function replayDlq() {
  return http.post('/sync/replay')
}

export function cancelJob(jobId) {
  return http.post(`/sync/jobs/${jobId}/cancel`)
}

export function getCacheStats() {
  return http.get('/dict/cache/stats')
}

export function importBioFile(file, operator = 'admin') {
  const formData = new FormData()
  formData.append('file', file)
  formData.append('operator', operator)
  return http.post('/dict/cache/import', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 120000
  })
}

export function refreshCache(entityType) {
  const params = entityType ? { entityType } : {}
  return http.post('/dict/cache/refresh', null, { params })
}

export function invalidateCache(entityType) {
  const params = entityType ? { entityType } : {}
  return http.delete('/dict/cache/invalidate', { params })
}

export function lookupDict(word, entityType = 'BRAND') {
  return http.get('/dict/cache/lookup', { params: { word, entityType } })
}
