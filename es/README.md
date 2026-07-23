# Elasticsearch SPU 索引

当前索引契约是一条 SPU 一个根文档，SKU 使用 `nested`，原因是价格、库存、规格必须命中同一个 SKU，不能把它们拆成互不相关的多值字段。`pro_supplier_sku_desc` 的 800 万行详情 HTML 不进入主索引。

关键词联想使用独立的 [`mall-search-suggestion-template-v1.json`](mall-search-suggestion-template-v1.json)，不与百万 SPU 主索引混用，说明见 [`docs/SEARCH_SUGGESTION_MODULE.md`](../docs/SEARCH_SUGGESTION_MODULE.md)。

## 创建与切换

先根据集群容量调整模板中的分片、副本和刷新周期，然后执行：

```bash
curl -X PUT "$ES/_index_template/mall-product-spu-template-v1" \
  -H 'Content-Type: application/json' \
  --data-binary @es/mall-product-spu-template-v1.json

curl -X PUT "$ES/mall-product-spu-v1-000001" \
  -H 'Content-Type: application/json' \
  -d '{"aliases":{"mall-product-spu-write":{"is_write_index":true}}}'
```

模板故意不自动绑定 read alias。全量回灌并校验文档数、抽样字段、搜索金标后，用 `_aliases` 一次原子操作把 `mall-product-spu-read` 从旧索引切到新索引。不要原地修改已上线 mapping。

应用侧切换：

```yaml
aimall:
  search:
    backend: auto
    elasticsearch-provider: rest
    elasticsearch:
      endpoint: http://your-es:9200
      index-alias: mall-product-spu-read
```

`auto` 下 ES 超时或异常会回退 MySQL。账号密码只放 `application-local.yml`、环境变量或密钥系统，不提交 Git。

## 同步边界

一次 SPU 文档重建需要读取：

- `pro_spu`：标题、类目、品牌、状态、权重、主图；
- `pro_sku` + `pro_sku_stock`：销售价、规格、条码、可用库存；
- `pro_platform_class` + `pro_platform_brand`：规范名称、路径、别名；
- `pro_spu_detail`：有则写评分/销量，无则写 0；
- 属性 JSON：解析成固定 `attrs` 列表，同时保留 `attrs_flat` 方便长尾等值检索。

任一 SKU/库存、品牌、分类变更都应回溯到 `spu_id`，幂等重建整个 SPU 文档。删除/下架使用同一套状态计算 `searchable=false` 或删除文档。同步事件至少携带 `event_id`、`entity_type`、`entity_id`、`spu_id`、`updated_time`；消费者按 `updated_time`/版本拒绝旧事件覆盖新文档。

全量建议按 SPU 主键游标分片读取，Bulk 每批从 5–15 MB 起测；增量使用公司已有 binlog/消息基础设施。具体 CDC 工具在确认现网组件后选择，当前接口不绑定 Canal、Debezium 或其他产品。

## 官方语义依据

- [nested query](https://www.elastic.co/guide/en/elasticsearch/reference/current/query-dsl-nested-query.html)：同一 SKU 内组合匹配；
- [flattened field](https://www.elastic.co/guide/en/elasticsearch/reference/current/flattened.html)：控制未知属性键造成的字段膨胀；
- [multi-fields](https://www.elastic.co/guide/en/elasticsearch/reference/current/multi-fields.html)：同一名称同时支持全文与精确过滤；
- [function_score](https://www.elastic.co/guide/en/elasticsearch/reference/current/query-dsl-function-score-query.html)：把人工权重、销量和评分加入召回分。
