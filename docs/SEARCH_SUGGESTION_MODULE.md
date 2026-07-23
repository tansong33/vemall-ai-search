# 搜索关键词联想模块

## 当前已经完成

接口：

```text
GET /api/search/suggestions?q=魔师&limit=8
```

当前 `DictionarySuggestionSource` 在内存中保存有效类目和品牌，支持：

- `保温` → `保温杯`：前缀；
- `用保` → `医用保温箱`：中间包含；
- `魔师` → `膳魔师`：后缀；
- `therm` → `膳魔师`：英文别名匹配后返回规范中文名。

`SearchSuggestionService` 负责多个 Source 的故障隔离、合并、去重、排序和限量。后续增加热词、用户历史或 ES 时，实现 `SuggestionSource` 即可，不修改 Controller 和前端协议。

当前词典快照是全局基线，`tenantCode/channelCode` 已进入 Source 接口但尚未用于词典分区；多租户生产环境必须使用下文 ES Source 的租户/渠道过滤，不能直接把本词典实现当成最终方案。

## 为什么当前不扫描商品标题

类目和品牌总量约 3.3 万，内存 contains 可以作为可运行基线；约百万 SPU/682 万 SKU 不可在用户每次按键时逐条扫描，也不能在线执行 `%关键词%`。

生产阶段使用 [`es/mall-search-suggestion-template-v1.json`](../es/mall-search-suggestion-template-v1.json) 建小型联想索引，首批只放：

1. 有效类目及关键词；
2. 有效品牌及中英文别名；
3. 审核后的高频历史 query；
4. 运营维护的商品类型词；
5. 必要时少量高销量 SPU 标题，不能直接灌全部 SKU 标题。

独立索引使用 `ngram` 支持前缀、中间和后缀匹配。`completion suggester` 更适合前缀补全，不能单独覆盖这里要求的任意位置关联。

## 下一阶段接口实现

新增 `ElasticsearchSuggestionSource`，查询规则建议为：

```json
{
  "size": 10,
  "track_total_hits": false,
  "query": {
    "bool": {
      "must": [{
        "multi_match": {
          "query": "魔师",
          "fields": ["text^3", "aliases^2"],
          "operator": "and"
        }
      }],
      "filter": [
        {"term": {"enabled": true}},
        {"term": {"tenant_codes": "TENANT-001"}},
        {"term": {"channel_codes": "RETAIL"}}
      ]
    }
  },
  "sort": ["_score", {"popularity": "desc"}]
}
```

生产验收至少包括：P95、单字/双字命中率、无结果率、错误租户泄漏、敏感词过滤、热词更新延迟和联想点击率。

## 前端行为

- 输入防抖 160ms；
- 新请求会取消上一次未完成请求；
- 支持上下方向键、回车、Esc 和鼠标选择；
- API 失败只隐藏联想框，不影响正式搜索；
- 当前选择候选后回填规范词并执行原搜索链路。
