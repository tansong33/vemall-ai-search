# API 契约

两个接口均接收和返回 `application/json`。响应统一使用 `ApiResponse<T>` 信封；金额单位为分，耗时单位为毫秒。

## 商品搜索

`POST /api/search`

请求体：

```json
{
  "query": "苹果 手机",
  "page": 1,
  "pageSize": 20,
  "sort": "RELEVANCE",
  "filters": {"brands": ["苹果"], "categories": ["手机"], "minPriceFen": 300000, "maxPriceFen": 800000, "inStock": true}
}
```

- `query` 必填、非空，最长 100 个字符；`page` 默认 1，`pageSize` 默认 20、最大 100。
- `sort` 可取 `RELEVANCE`、`PRICE_ASC`、`PRICE_DESC`、`SALES`、`RATING`。
- `filters` 可省略；`brands` 和 `categories` 传名称，价格上下限均为分。

成功响应：

```json
{
  "success": true,
  "code": "OK",
  "message": null,
  "requestId": "7d9418b323f44783",
  "data": {
    "total": 127,
    "rawTotal": 154,
    "page": 1,
    "pageSize": 20,
    "items": [
      {"skuId": "SKU-1001", "spuId": "SPU-1001", "title": "苹果手机 256GB", "highlightTitle": "<em>苹果</em>手机 256GB", "brandName": "苹果", "categoryName": "手机", "imageUrl": "https://example.com/sku-1001.jpg", "priceFen": 599900, "inStock": true, "score": 86.5}
    ],
    "facets": {
      "brands": [{"key": "苹果", "count": 81}],
      "categories": [{"key": "手机", "count": 127}]
    },
    "tookMs": 36,
    "cacheStatus": "MISS",
    "degraded": false,
    "degradeReasons": []
  }
}
```

`total` 是 SPU 去重后的数量，`rawTotal` 是 ES 去重前命中数。`cacheStatus` 为 `HIT`、`MISS` 或 `UNAVAILABLE`；`degradeReasons` 的值限定为 `REDIS_UNAVAILABLE`、`NER_UNAVAILABLE`、`MODEL_UNAVAILABLE`、`STALE_CACHE`、`DEDUP_SKIPPED`。

## 搜索链路调试

`POST /api/debug/pipeline`

请求体继承商品搜索请求的全部字段，并增加 `includeEsDsl`：

```json
{
  "query": "苹果 手机",
  "page": 1,
  "pageSize": 20,
  "sort": "RELEVANCE",
  "filters": {"brands": ["苹果"], "categories": ["手机"], "minPriceFen": 300000, "maxPriceFen": 800000, "inStock": true},
  "includeEsDsl": true
}
```

`includeEsDsl` 默认为 `false`；只有为 `true` 时才返回原始 ES DSL。

成功响应：

```json
{
  "success": true,
  "code": "OK",
  "message": null,
  "requestId": "66242ac414894457",
  "data": {
    "result": {
      "total": 127, "rawTotal": 154, "page": 1, "pageSize": 20,
      "items": [{"skuId": "SKU-1001", "spuId": "SPU-1001", "title": "苹果手机 256GB", "highlightTitle": "<em>苹果</em>手机 256GB", "brandName": "苹果", "categoryName": "手机", "imageUrl": "https://example.com/sku-1001.jpg", "priceFen": 599900, "inStock": true, "score": 86.5}],
      "facets": {"brands": [{"key": "苹果", "count": 81}], "categories": [{"key": "手机", "count": 127}]},
      "tookMs": 36, "cacheStatus": "MISS", "degraded": false, "degradeReasons": []
    },
    "ner": {
      "entities": [{"text": "苹果", "label": "BRAND", "start": 0, "end": 2, "confidence": 0.99, "normalizedText": "Apple", "normalizedId": "brand-apple"}],
      "provider": "onnx+dictionary", "mode": "hybrid", "modelVersion": "raner-v1", "modelReady": true, "costMs": 8
    },
    "query": {"rewrittenQuery": "苹果 手机", "synonyms": ["智能手机"], "analyzedTokens": ["苹果", "手机"], "costMs": 3},
    "es": {
      "mustClauses": ["品牌匹配: 苹果"], "shouldClauses": ["标题匹配: 智能手机"],
      "mustNotClauses": ["排除配件: 手机壳"], "dsl": "{\"query\":{\"bool\":{}}}", "totalHits": 154, "costMs": 25
    },
    "cache": {"status": "MISS", "key": "search:result:products-v3:dict-v12:raner-v1:rule-v4:a1b2c3d4e5f60718:b2c3d4e5f6071829:1", "lookupMs": 1},
    "timing": {"totalMs": 38, "cacheMs": 1, "nerMs": 8, "queryMs": 3, "esMs": 25}
  }
}
```

`ner.provider` 表示实际识别器，`ner.mode` 表示配置模式；`cache.status` 可为 `HIT`、`MISS` 或 `UNAVAILABLE`。`result` 字段与商品搜索响应的 `data` 结构完全一致。

## 错误码

| HTTP | `code` | 含义 |
|---:|---|---|
| 400 | `INVALID_ARGUMENT` | 请求参数校验失败 |
| 503 | `SEARCH_UNAVAILABLE` | ES 不可用且没有可用缓存 |
| 500 | `INTERNAL_ERROR` | 未分类的服务端错误 |

错误响应示例：

```json
{"success": false, "code": "INVALID_ARGUMENT", "message": "query 不能为空", "requestId": "088297102f7445c4", "data": null}
```
