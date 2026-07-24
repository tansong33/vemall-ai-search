// 搜索 API 封装
import axios from 'axios'

// 创建 axios 实例，配置基础 URL 和超时
const http = axios.create({
  baseURL: '/api',      // vue.config.js 中 proxy 代理到 localhost:8080
  timeout: 10000        // 10 秒超时
})

// 响应拦截器：统一处理错误
http.interceptors.response.use(
  response => response.data,
  error => {
    const msg = error.response?.data?.message || error.message || '请求失败'
    return Promise.reject(new Error(msg))
  }
)

/**
 * 搜索 Pipeline 全链路接口
 * 返回每一步的中间结果供前端可视化
 *
 * @param {Object} params - { query: string, filters?: Array }
 * @returns {Promise<SearchPipelineResponse>}
 */
export function searchPipeline(params) {
  return http.post('/search/pipeline', params)
}

/**
 * 单独 NER 识别（供调试用）
 */
export function nerAnalyze(query) {
  return http.post('/search/ner', { query })
}

/**
 * ES 分词分析（供调试用）
 */
export function esAnalyze(text, analyzer = 'ik_max_word') {
  return http.post('/search/analyze', { text, analyzer })
}
