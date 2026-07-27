import axios from 'axios'

const http = axios.create({
  baseURL: '/api',
  timeout: 10000
})

http.interceptors.response.use(
  response => {
    const body = response.data
    if (!body || typeof body !== 'object' || typeof body.success !== 'boolean') {
      return Promise.reject(new Error('响应格式错误'))
    }
    if (body.success) {
      return body.data
    }
    return Promise.reject(new Error(body.message || body.code || '请求失败'))
  },
  error => {
    const body = error.response && error.response.data
    const message = (body && (body.message || body.code)) || error.message || '请求失败'
    return Promise.reject(new Error(message))
  }
)

export function search(params) {
  return http.post('/search', params)
}

export function debugPipeline(params) {
  return http.post('/debug/pipeline', params)
}

export function health() {
  return axios.get('/actuator/health').then(response => response.data)
}

export const SORT_MAP = {
  default: 'RELEVANCE',
  price_asc: 'PRICE_ASC',
  price_desc: 'PRICE_DESC',
  sales: 'SALES',
  rating: 'RATING'
}
