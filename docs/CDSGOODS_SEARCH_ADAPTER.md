# cdsgoods 搜索适配说明

> 契约版本：`mall-spu-v1`<br>
> 当前依据：已提供的 11 张表结构梳理；精确 DDL 到位后只允许修改 mapper/provider 和本映射文档，不把物理表字段扩散到规则引擎。

## 1. 已冻结的业务口径

| 项目 | 当前实现 |
|---|---|
| 返回粒度 | 一张卡一个 SPU，同时返回本次查询最合适的 `skuId` |
| 显示/过滤价格 | `pro_sku.sales_price`；卡片附可售 SKU 的 min/max |
| 可用库存 | `max(total_stock_num - locked_stock_num, 0)` |
| 可售条件 | SPU 上架且审核通过且未删除；SKU 上架且未删除；可用库存 > 0 |
| 类目/品牌 | 返回规范 ID + 名称；NER 词典包含分类 keywords、品牌中英文名和 aliases |
| 排序特征 | BM25/FULLTEXT、`search_weight`、销量、评分、规则匹配、预算贴合 |
| 详情表 | `pro_supplier_sku_desc` 不进在线主查询，也不进 ES 主索引 |
| 多租户 | `tenantCode`、`channelCode` 进入 SQL/ES filter 和缓存 key |

所有状态值在 `aimall.search.status` 配置中。后续若枚举定义变化，不改 SQL 字符串。

## 2. 代码边界

```text
ProductCatalogRepository
  ├─ DemoProductCatalogRepository       → 本仓库 product 样例表
  └─ CdsgoodsProductCatalogRepository   → 公司 pro_spu/pro_sku/... 表

两套适配器
  → Product（SPU + matched SKU 标准对象）
  → ProductRuleEngine / ResponseAssembler
```

公司物理表只出现在 `CdsgoodsProductMapper` 和 `CdsgoodsProductSearchSqlProvider`。页面、NER、规则、缓存和 ES 召回不允许引用 `pro_*` 字段。

## 3. SQL 查询流程

```text
pro_spu：FULLTEXT + 状态 + 租户/渠道 + 类目/品牌
  → LIMIT spuCandidateLimit（默认 1000）
  → 仅对候选 SPU 关联 pro_sku + pro_sku_stock
  → 价格/可用库存硬过滤
  → row_number() 每个 SPU 选一个最佳 SKU
  → LIMIT candidateLimit（默认 200）
  → Java 规则精排 Top 20
```

精确编号支持 SPU ID、SKU ID、条码、供应商 SKU ID，固定走 MySQL。普通搜索以 ES 为主时，MySQL 仍是限流后的故障兜底。

## 4. ES 文档映射

| 数据源 | ES 字段 |
|---|---|
| `pro_spu.id/pro_name` | `spu_id/title` |
| suggestion/keywords | `suggestion/query_keywords` |
| class + 父级链 | `category.id/name/path_ids/path_names/keywords` |
| brand | `brand.id/name/aliases/parent_id` |
| `pro_sku` + stock | `skus[]` nested |
| SKU 销售价聚合 | `min_price/max_price` |
| `pro_spu.min_purchase_num` | `min_purchase_num`，采购数量硬过滤 |
| SKU 可用库存聚合 | `total_available_stock/has_stock` |
| `search_weight` | `search_weight` |
| `pro_spu_detail` | `sales_count/review_count/rating/good_rate`，缺失填 0 |
| 状态计算 | `searchable` |

未知属性名不可直接 dynamic 展开，否则会产生 mapping explosion。同步层需把属性规范化为 `attrs[]`，并用 `attrs_flat` 保存长尾键值。

## 5. 本地/联调切换

本地默认：

```yaml
aimall.search.data-source: demo
aimall.search.backend: mysql
```

公司开发库：

```yaml
spring.datasource.url: jdbc:mysql://HOST:3306/cdsgoods?...
aimall.search.data-source: cdsgoods
aimall.search.backend: mysql
aimall.search.require-tenant-context: true
```

ES 小流量联调：

```yaml
aimall.search.data-source: cdsgoods
aimall.search.backend: auto
aimall.search.elasticsearch-provider: rest
```

`auto` 会在 ES 异常时回退 MySQL。任何密码、内网地址、真实商品数据和 query 日志都不能提交仓库。

## 6. 第 5 项联调数据验收

收到 100–500 个 SPU（含 1,000–5,000 SKU）后：

1. 放到 `integration-data/catalog/`（已被 `.gitignore` 忽略）；
2. 覆盖单/多 SKU、缺库存、下架、审核未通过、无品牌、无评分、JSON 空值与异常值；
3. 对同一批 ID 比较 MySQL 事实、标准 `Product`、ES `_source` 和 API 卡片；
4. 验证价格、库存、状态与租户隔离 100% 一致；
5. 跑 `EXPLAIN ANALYZE`，记录 100 个代表 query 的 P50/P95/P99。

数据清单格式见 `integration-data/README.md`。真实数据只在内部安全存储流转。

## 7. 第 6 项 Query/NER 验收

收到 500–2,000 条脱敏 query 后，转换成：

```json
{"query_id":"q_hash","text":"给员工买50元以内的保温杯","group_id":"session_hash","source":"search_log"}
```

再使用 `ml/src/data_build_annotation_tasks.py` 做词典预标，人工在 Label Studio 复核，导出后按 group/time 切 train/dev/test。已有 Doccano 导出只走历史兼容转换。价格、数量、明确 ID 继续由规则抽取，不占 NER 标签。

## 8. 尚未冻结、便于后改的项目

- 状态枚举的最终值及供应商/采购方状态联动；
- 积分资格、专票、Logo 定制对应的真实字段；
- 合同价、会员价、渠道价是否覆盖 `sales_price`；
- 多仓库存与预售库存是否覆盖当前公式；
- 分类编码是否全部严格采用三级前缀规则；
- ES 分片数、中文 analyzer 和 CDC 产品选型。

这些差异分别收敛在配置、属性规范化器、价格/库存策略、ES template 和同步适配器，不改在线 Pipeline API。
