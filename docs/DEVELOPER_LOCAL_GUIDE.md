# AI 商城智能搜索：开发人员上手与改进手册

> 版本：V1.0<br>
> 基线日期：2026-07-22<br>
> 适用对象：Java、NER、ES/同步、规则评测、缓存性能开发<br>
> 约束：在线主服务保持 Java 8；不要把任何真实密码或未脱敏数据提交代码仓库

## 1. 这份手册解决什么问题

这是一份可以脱离 GitHub 页面单独阅读的开发指南。拿到项目压缩包或源码目录后，开发人员应能完成：

1. 判断当前项目是 POC 框架还是生产成品；
2. 在本机用 demo 数据启动服务；
3. 理解一次搜索和一次关键词联想的执行路径；
4. 找到自己负责模块的代码入口；
5. 领取一个不会阻塞其他人的首个任务；
6. 知道提交功能时需要哪些测试、指标和文档。

当前框架有可运行的主链路和扩展点；尚未提供真正微调后的 NER 模型、生产级商品同步和真实相关性/容量结论。

## 2. 技术基线与边界

| 项目 | 当前选择 | 边界 |
|---|---|---|
| Java | JDK 8 字节码 | 不升级主服务 Java 版本 |
| Web | Spring Boot 2.7.18 | Controller 只做校验和协议转换 |
| 数据访问 | MyBatis-Plus + MySQL 8 | SQL 必须参数化、有候选上限、可解释 |
| 搜索 | ES REST 可选，MySQL 有界兜底 | 核心业务不绑定某个 ES Java Client |
| NER | 规则/词典在线基线；模型端口可切换 | fixture 只用于联调，不能代表模型准确率 |
| 缓存 | Caffeine L1 + 可选 Redis L2 | key 必须包含租户、渠道和版本 |
| 返回粒度 | SPU 商品卡 + 最佳匹配 SKU | 变更该口径必须先改契约和评测集 |
| 商品事实源 | MySQL | ES 和 Redis 都应可重建/失效 |

禁止恢复以下实现：在线同步调用生成式大模型、把 682 万 SKU 读入 JVM、逐条计算向量、`LIKE '%词%'` 全表主召回、逐商品 N+1 查询、商品列表返回前同步生成导购文案。

## 3. 五分钟理解目录

```text
ai-mall-demo/
├─ src/main/java/cn/vetech/aimall/
│  ├─ controller/                 HTTP API
│  ├─ config/                     配置与实现选择
│  ├─ mapper/                     demo/cdsgoods SQL 入口
│  ├─ model/                      DTO、实体、检索对象、ES 文档契约
│  ├─ repository/                 物理表适配；上层不感知 demo/cdsgoods
│  └─ service/
│     ├─ RecommendPipeline.java   一次搜索的总编排与阶段计时
│     ├─ ProductSearchService.java 精确/普通检索路由
│     ├─ ProductRuleEngine.java   硬过滤与软排序
│     ├─ SearchCacheService.java  L1/L2 缓存
│     ├─ ner/                     规则、词典、模型端口、shadow/hybrid
│     ├─ recall/                  MySQL/ES 召回通道与自动回退
│     └─ suggestion/              关键词联想源与聚合
├─ src/main/resources/
│  ├─ application.yml             默认配置与权重
│  ├─ application-local-template.yml
│  └─ static/index.html           Demo 搜索页与联想交互
├─ sql/                            demo 表、搜索索引、公司库索引建议
├─ es/                             商品/联想 mapping 与样例文档
├─ integration-data/               脱敏联调数据约定与示例
├─ ml/                             标注转换、校验、切分、NER 评分工作区
└─ docs/                           业务口径、适配、模块与路线文档
```

## 4. 推荐阅读顺序

### 所有人先读

1. `README.md`：项目定位、启动、API 和上线边界；
2. `docs/MEETING_PROJECT_FLOW_AND_ROLES.md`：团队分工和本阶段交付；
3. `src/main/java/cn/vetech/aimall/service/RecommendPipeline.java`：主链路；
4. `src/main/resources/application.yml`：当前所有开关、阈值和版本。

### 按岗位继续读

| 岗位 | 阅读入口 |
|---|---|
| Java 集成 | `ProductSearchService`、`RecallOrchestrator`、`ResponseAssembler`、DTO |
| NER | `service/ner/`、`ml/README.md`、`docs/NER_AND_SEARCH_DEVELOPMENT_GUIDE.md` |
| ES/同步 | `service/recall/`、`es/README.md`、`docs/CDSGOODS_SEARCH_ADAPTER.md` |
| 规则评测 | `ProductRuleEngine`、`SearchCriteria`、`application.yml` rerank 配置 |
| 缓存性能 | `SearchCacheService`、`AiMallProperties`、Pipeline trace |
| 搜索联想 | `service/suggestion/`、`SuggestionController`、`docs/SEARCH_SUGGESTION_MODULE.md` |

使用 IDE 的“转到定义/查找用法”沿接口阅读，先看边界再看具体实现，不必从 Controller 逐文件通读。

## 5. 本地启动

### 5.1 环境要求

- JDK 8；也可以用更高 JDK 执行 Maven，但编译目标仍必须是 Java 8；
- Maven 3.8+；
- MySQL 8；
- Redis 可选，默认关闭；
- Elasticsearch 可选，默认走 MySQL + ES stub。

检查环境：

```powershell
java -version
mvn -version
git status --short
```

### 5.2 使用 demo 数据

1. 在本地 MySQL 导入 `sql/product.sql`；
2. 执行 `sql/search_optimization.sql` 建立搜索索引；
3. 复制本地配置：

```powershell
Copy-Item src/main/resources/application-local-template.yml src/main/resources/application-local.yml
```

4. 在 `application-local.yml` 填本机数据库连接；不要修改模板来保存个人密码；
5. 构建与启动：

```powershell
mvn clean test
mvn spring-boot:run
```

6. 浏览器打开 `http://localhost:8080/`。

### 5.3 接公司 cdsgoods 数据

不要在公司库执行 demo 建表脚本。先让 DBA 审核 `sql/cdsgoods_search_indexes.sql`，然后在个人本地配置覆盖：

```yaml
aimall:
  search:
    data-source: cdsgoods
    require-tenant-context: true
    backend: mysql
```

当前 V1 口径：

- SPU：`pro_spu`；
- SKU：`pro_sku`；
- 库存：`pro_sku_stock`；
- 类目/品牌：`pro_platform_class`、`pro_platform_brand`；
- 价格：`pro_sku.sales_price`；
- 可售库存：`total_stock_num - locked_stock_num`；
- 返回：一个 SPU 商品卡，携带最符合条件的 SKU；
- 所有业务主键按字符串处理，库存按 `BigDecimal` 处理。

这些仍是可配置/可替换口径。拿到精确文本 DDL、状态枚举和索引清单后，应先修适配层，不要把公司字段扩散到 Pipeline。

## 6. API 快速验证

### 6.1 关键词联想

```text
GET http://localhost:8080/api/search/suggestions?q=魔师&limit=8
```

当前词典基线支持规范名、别名、完全、前缀、中间和后缀匹配。前端有 160 ms 防抖、取消旧请求、上下键和回车选择。生产 ES ngram 联想源尚未实现。

### 6.2 搜索

```powershell
$body = @{
  tenantCode = "TENANT-001"
  channelCode = "RETAIL"
  query = "夏天办公室降暑的员工福利，预算50元以内，要现货"
} | ConvertTo-Json

Invoke-RestMethod `
  -Method Post `
  -Uri http://localhost:8080/api/recommend `
  -ContentType "application/json" `
  -Body $body
```

重点检查响应：

- `intent`：Query 被抽取出的品牌、类目、场景、价格和属性；
- `products`：真实数据库商品，不是模型生成；
- `trace`：NER、召回、规则和总耗时，以及实际通道路由；
- `fromCache`：是否命中缓存。

### 6.3 分页抽样

```text
GET http://localhost:8080/api/products?afterId=&size=20
```

这是 varchar 主键游标分页，只用于联调抽样；不要把它当 682 万 SKU 全量导出接口。

### 6.4 NER 状态与词典刷新

```text
GET  http://localhost:8080/api/admin/ner/status
POST http://localhost:8080/api/admin/ner-dictionary/refresh
```

`model-provider=fixture` 只能验证 shadow/hybrid、阈值和回退路径，不能汇报模型准确率。

## 7. 一次搜索的代码执行路径

```text
RecommendController
  → RecommendPipeline
       1. 校验/规范化 Query 与租户渠道
       2. SearchCacheService 查询 L1/L2
       3. HybridIntentRecognizer
            ├─ RuleBasedNerService
            └─ NerModelClient（stub/fixture/未来 ONNX）
       4. ProductSearchService 判断精确 ID 或普通需求
       5. RecallOrchestrator
            ├─ RestElasticsearchRecallChannel
            └─ MySQL fallback / repository
       6. ProductRuleEngine 硬过滤、软打分、Top N
       7. ResponseAssembler 输出商品卡和非生成式说明
       8. 写缓存并返回 SearchTrace
```

改代码前先判断修改属于“理解 Query”“候选召回”“商品事实”“硬规则”“排序”“缓存”还是“展示协议”。同一个判断不要同时放在两个阶段。

## 8. 关键词联想的代码执行路径

```text
SuggestionController
  → SearchSuggestionService
       → 一个或多个 SuggestionSource
            └─ DictionarySuggestionSource（当前）
       → 规范化、合并、去重、按匹配类型/权重排序
       → SuggestionResponse
```

下一阶段推荐增加：

1. `ElasticsearchSuggestionSource`：查询 `mall-search-suggestion-*` ngram 索引；
2. 热搜词/历史点击词 Source；
3. 合并时区分完全、前缀、中间、后缀、热度和个性化；
4. 拼音首字母与纠错只在离线评测证明收益后加入；
5. 任何 Source 超时应被隔离，联想接口不得拖慢提交搜索。

## 9. 配置与版本规则

核心配置位于 `src/main/resources/application.yml`，个人覆盖放在被忽略的 `application-local.yml`。

| 配置组 | 用途 | 修改要求 |
|---|---|---|
| `aimall.search` | 数据源、候选上限、MySQL/ES 路由、状态值 | 改字段/上限时补 SQL 或召回测试 |
| `aimall.rerank` | 数据库分、规则分、主推和预算权重、Top N | 只能依据冻结相关性集调参 |
| `aimall.cache` | L1/L2、TTL、容量、Redis 故障退避 | key 维度变化必须提升 cache version |
| `aimall.ner` | rule/shadow/hybrid/model、模型版本、阈值、回退 | 模型切换先 shadow，保留 rule fallback |
| `aimall.suggestion` | 开关、最短长度、最大结果、刷新周期 | 变更排序时补联想验收集 |
| `aimall.versions` | schema/rule/index/cache 版本 | 影响协议或结果的变化必须显式升版本 |

不要在代码里散落租户、渠道、状态或权重常量。不能确定的公司枚举先放配置并记录待确认事项。

## 10. 开发 1：架构与 Java 集成任务说明

### 第一个任务

冻结 V1 接口和异常/降级契约。

### 修改入口

- `RecommendPipeline.java`
- `ProductSearchService.java`
- `RecallOrchestrator.java`
- `IntentResult.java`、`ProductCard.java`、`SearchCriteria.java`
- `AiMallProperties.java`

### 建议改进

1. 为请求校验、NER、召回、规则、组装、缓存定义稳定的阶段名；
2. 区分业务零结果、外部超时、依赖不可用、数据不一致和程序错误；
3. 给每条降级路径增加 machine-readable reason；
4. 明确各阶段超时预算和总超时；
5. 增加 API 契约测试、ES→MySQL 回退测试、模型→规则回退测试；
6. 用 ADR 记录会影响其他模块的选择。

### 验收证据

- `mvn clean test` 通过；
- 同一请求在 stub/真实实现间出参 schema 不变；
- 注入 ES/模型/Redis 故障后仍能按约定降级；
- trace 能说明走了哪条路径以及为什么降级。

## 11. 开发 2：数据与 NER 模型任务说明

### 第一个任务

先完成标签规范和数据闭环，再训练模型。不要直接把 500 万商品行当 NER 训练集。

### 训练样本长什么样

```json
{
  "text": "给员工买50元以内的膳魔师保温杯",
  "entities": [
    {"start": 1, "end": 3, "text": "员工", "label": "SCENE"},
    {"start": 10, "end": 13, "text": "膳魔师", "label": "BRAND"},
    {"start": 13, "end": 16, "text": "保温杯", "label": "PRODUCT_TYPE"}
  ]
}
```

价格、数量、商品 ID 等确定性实体优先由规则抽取，不必强迫模型学习。

### 数据来源优先级

```text
脱敏真实历史 Query
  > 人工设计的关键业务 Query
  > 商品库词典/模板生成 Query
  > 大模型离线改写或生成的候选 Query
```

AI 可以做预标注和扩写，但进入 Gold 的样本必须人工复核。难例和 test 集建议双人标注，冲突由产品 1 仲裁并补充规范。

### 第一阶段数据安排

- 200 条：验证标签边界和工具；
- 500～2,000 条：第一轮 POC，保证品牌/类目/场景/属性和困难切片覆盖；
- 继续扩充：由错误分析决定，不追求盲目堆数量；
- train/dev/test 按规范化 Query 或 Query family 分组切分，避免同模板泄漏；
- test 一旦冻结，只用于版本验收，不参与调参。

### 模型路线

1. 下载并固定 `hfl/minirbt-h256` 的版本、tokenizer、许可证和 checksum；
2. 添加商城 token-classification head 并微调，不是从零训练大模型；
3. 起跑参数参见 `docs/PROJECT_EXECUTION_AND_TEAM_PLAN.md`；
4. 首轮只比较学习率、epoch、max length、batch size、dropout 和随机种子；
5. 输出 strict entity precision/recall/F1、分标签指标、OOV 品牌、否定/歧义困难切片；
6. 只有 MacBERT 明显更准且 MiniRBT 未达标时，再评估 Teacher→Student 蒸馏；
7. 导出 ONNX/可选 INT8，做 Python 与 Java token/span/置信度一致性测试；
8. 先 `shadow`，再 `hybrid`，最后才考虑 `model` 主导。

### 当前代码入口

- 数据工具：`ml/src/`；
- 操作说明：`ml/README.md`；
- Label Studio 多人标注：`ml/annotation/LABEL_STUDIO_WORKFLOW.md`；
- Doccano 历史导出兼容：`ml/annotation/DOCCANO_WORKFLOW.md`；
- 在线端口：`NerModelClient`；
- 规则基线：`RuleBasedNerService`；
- 策略切换：`HybridIntentRecognizer`；
- 词典：`EntityDictionaryService`。

### 验收证据

- 数据卡：来源、脱敏、标签分布、切分方法、版本和已知偏差；
- 固定 test 集 strict entity F1 和各标签 P/R/F1；
- hard slice 报告：OOV 品牌、否定、英文/数字、错别字、长句；
- ONNX 制品清单、checksum、labels、thresholds、tokenizer；
- shadow 日志不包含隐私原文或已按公司规范脱敏。

## 12. 开发 3：ES、召回与同步任务说明

### 第一个任务

用 1 万条脱敏商品跑通“构建新索引—写入—查询—对账—alias 切换”，同时实现 ES 联想 Source。

### 修改入口

- `es/mall-product-spu-template-v1.json`
- `es/mall-search-suggestion-template-v1.json`
- `ElasticsearchQueryFactory.java`
- `RestElasticsearchRecallChannel.java`
- `service/suggestion/SuggestionSource.java`
- `model/index/` 中的索引文档契约
- `CdsgoodsProductSearchSqlProvider.java` 和 repository 适配

### 索引原则

- 一 SPU 一根文档，多个 SKU 放 nested；
- ID/状态/租户/渠道用于精确过滤，采用 keyword；
- 名称和描述采用 text，需要精确能力时加 keyword/raw 子字段；
- 高基数属性不能长期作为不可查询的 JSON 字符串；
- 分词器和字段 boost 用 Recall/NDCG 比较，不凭分词展示效果决定；
- 每次 mapping 大变更创建新版本索引，校验后切 alias，不原地冒险修改。

### 同步 Worker 必备能力

1. 全量分页/游标读取，Bulk 限流；
2. binlog/CDC 增量 upsert/delete；
3. 事件幂等、乱序保护、失败重试和死信；
4. 全量期间增量追平；
5. 数量、ID 和关键字段对账；
6. alias 切换、重建和回滚；
7. 库存高频更新策略与可接受延迟明确。

### 验收证据

- 1 万条样本索引数量和关键字段对账；
- 精确 ID、品牌/类目、价格/库存、长句 Query 测试；
- Recall@50/200 与零结果率报告；
- 重复、乱序、删除、重放和 ES 暂停时的测试；
- 回滚到旧 alias 的演练记录。

## 13. 开发 4：规则、排序与相关性评测任务说明

### 第一个任务

建立 100～200 条 Golden Query，每条保存业务条件和候选商品 0～3 级相关性判断。

### Judgment 示例

| Query | 商品/条件 | 等级 | 原因 |
|---|---|---:|---|
| 50 元以内员工福利保温杯 | 45 元、有库存、保温杯、可批量 | 3 | 完全满足 |
| 50 元以内员工福利保温杯 | 49 元、有库存、普通水杯 | 2 | 类目相近但属性不完整 |
| 50 元以内员工福利保温杯 | 89 元保温杯 | 1 | 文本相关但违反预算 |
| 50 元以内员工福利保温杯 | 手机壳 | 0 | 不相关 |

库存、租户、上下架等硬约束不应只依赖 relevance 分；违反硬约束的商品应被过滤并单独计入 Constraint Violation。

### 修改入口

- `ProductRuleEngine.java`
- `SearchCriteria.java`
- `application.yml` 的 `aimall.rerank`
- 后续新增离线 `evaluation/` 数据和脚本

### 必须报告的指标

- Recall@50、Recall@200：正确商品是否进入候选；
- MRR：第一个高相关商品的位置；
- NDCG@10、NDCG@20：多级相关性的排序质量；
- Zero Result Rate：零结果比例；
- Constraint Violation：超预算、无库存、状态/属性冲突比例；
- 按 Query 类型分片：精确 ID、品牌类目、价格属性、场景长句、长尾/OOV。

### 调优规则

- 先修数据和硬规则，再调权重；
- 每次只改变少量参数并保存版本、基线和差异；
- 不能用 test 集选择权重；
- NER、mapping、同义词、召回和排序的每次变化都跑同一回归集；
- 产品 2 对争议 judgment 做最终仲裁。

### 验收证据

- 可重复执行的评测命令；
- 数据版本、规则版本、索引版本和结果报告绑定；
- Top 失败 Query 的人工原因分析；
- 修改前后 NDCG/违规率/零结果率对比，而不是只给几张搜索截图。

## 14. 开发 5：缓存、性能与工程化任务说明

### 第一个任务

冻结缓存 key/TTL/版本策略，并记录现有规则 + MySQL 基线的 P50/P95/P99。

### 修改入口

- `SearchCacheService.java`
- `AiMallProperties.java`
- `application.yml` 的 `aimall.cache` 和 `aimall.versions`
- `RecommendPipeline.java` 的阶段 trace/metrics

### 缓存 key 至少包含

```text
tenant + channel + normalizedQuery + userPriceScope（若有）
+ schemaVersion + modelVersion + ruleVersion + indexVersion + cacheVersion
```

### 生产策略

- TTL 加随机抖动，防止同一时刻大面积失效；
- 对确定性的空结果做短 TTL 负缓存，但不能掩盖同步延迟；
- 热 key 预热和本地缓存隔离；
- Redis 超时短、失败快速、退避后探测，不允许线程堆积；
- 多实例下处理缓存一致性，但不把缓存当事实源；
- 密钥走环境变量/密钥系统，不写仓库；
- 缓存维度或结果协议变化时提升版本，避免反序列化旧值。

### 压测组成

- 40% 高频短 Query，可形成缓存命中；
- 20% 长尾 Query；
- 15% SPU/SKU/条码精确查询；
- 15% 多条件自然语言长句；
- 10% 零结果、错别字、超长或非法输入。

分别测试冷缓存、热缓存、Redis 不可用、ES 超时、MySQL 慢查询和模型异常。记录应用 CPU/内存/GC/线程、连接池、各阶段延迟、缓存命中率和错误/降级率。

### 验收证据

- 可复现的压测脚本、数据组成、环境与并发参数；
- P50/P95/P99 和吞吐量，不只给平均耗时；
- Redis/ES/模型故障演练和恢复结果；
- Dashboard、告警阈值、runbook 草案。

## 15. 跨团队接口冻结清单

首次多人并行前至少冻结以下对象/接口的 V1：

- `IntentResult` 与模型输出的 span、label、confidence；
- `SearchCriteria`/未来 `QueryPlan`；
- `RecallChannel` 的输入、候选分和错误语义；
- `Product`/未来 `ProductSnapshot` 的 SPU + matched SKU 字段；
- `SuggestionSource` 的匹配类型、显示值、标准 ID 和分数；
- 缓存 key 与 `SearchTrace` 的版本字段；
- API 请求/响应和错误码；
- ES 商品文档和同步事件格式。

接口冻结不表示永远不改；表示破坏性修改必须提升版本、提供迁移/兼容说明，并通知所有消费者。

## 16. 测试与提交标准

### 本地最低检查

```powershell
mvn clean test
mvn clean package
git diff --check
git status --short
```

当前基线为 23 个 Java 测试通过。数量以后可能增长，验收以测试全部通过为准，不要把“23”写进自动化断言。

### 分层测试

| 层级 | 目标 | 示例 |
|---|---|---|
| Unit | 单个规则/解析/排序确定性 | 价格区间、库存、后缀联想、NER 冲突 |
| Contract | 接口的 schema 和错误语义稳定 | NER/Recall/Suggestion stub 与真实实现一致 |
| Integration | MySQL/Redis/ES 的真实协议 | SQL 结果映射、REST Query、缓存序列化 |
| Regression | 版本变化不破坏冻结样本 | strict F1、Recall@K、NDCG、违规率 |
| Performance | 容量、长尾和失败场景 | 冷热缓存、慢 ES、Redis 中断、并发 |

### 一个功能提交必须包含

- 需求/问题和修改范围；
- 设计选择与未选择方案的简短原因；
- 新增或更新测试；
- 配置、接口、索引、数据版本变化；
- 验证命令和结果；
- 风险、回退方式和后续事项；
- 对应文档更新。

## 17. 分支和协作建议

建议短生命周期分支：

```text
feature/java-contract-v1
feature/ner-training-baseline
feature/es-sync-worker
feature/relevance-evaluation
feature/cache-loadtest
feature/es-suggestion-source
```

提交原则：

1. 一次提交解决一个可说明的问题；
2. 不混入 IDE、密码、模型大文件、真实 Query 或无关格式化；
3. 跨模块接口修改先开短设计说明或 ADR；
4. mapping、规则、模型和数据都用版本标识；
5. 每周至少一次主干集成，不让各分支持续漂移；
6. 合并前由该模块之外的一人评审，关键业务规则由对应产品验收。

## 18. 数据、安全与 Git 忽略规则

以下内容不应进入普通 Git 历史：

- `application-local.yml`、`.env` 和任何账号密码；
- 未脱敏历史 Query、用户/订单/企业身份信息；
- 真实数据库导出；
- 大型训练 checkpoint、ONNX/量化制品和实验缓存；
- Label Studio 本地数据库，以及已有 Doccano 历史数据库；
- IDE、日志、压测临时文件。

代码仓库只保存：可公开/内部共享的脱敏小样本、schema、配置模板、训练/评测代码、模型制品清单与 checksum。真实数据和大型模型进入有权限、可审计的对象存储或制品库。

## 19. 常见故障定位

### 启动时数据库连接失败

- 检查 `application-local.yml` 是否存在且 profile 为 local；
- 检查 URL、库名、时区、账号权限；
- demo 与 cdsgoods 数据源配置不要混用；
- 不要把本地密码写回模板。

### 搜索一直走 MySQL

- 检查 `aimall.search.backend` 是否为 `auto` 或 `elasticsearch`；
- 检查 `elasticsearch-provider` 是否为 `rest`；
- 检查 endpoint、alias 和超时；
- 查看 trace 是否记录 ES 失败并自动回退。

### NER 看起来像模型但结果不真实

- 查看 `/api/admin/ner/status`；
- `stub` 表示没有模型；`fixture` 表示词条模拟；
- 只有加载带版本的 ONNX 制品并通过 parity/Gold 评测后，才算真实模型结果。

### Query 有品牌却没有识别

- 检查品牌是否启用、租户/渠道是否过滤；
- 刷新 NER 词典；
- 检查别名和大小写规范化；
- 把该 Query 加入困难样本，而不是只加硬编码 if。

### 召回很多但排序不对

- 先区分召回问题还是排序问题；
- 看正确商品是否在 Recall@200 中；
- 若不在，修 analyzer/同义词/query；若已在，修规则/权重；
- 用 Golden Query 比较，不要只看单个案例。

### 延迟突然升高

- 先看阶段 trace：缓存、NER、ES/DB、规则哪个变慢；
- 检查缓存命中率、连接池、线程池、GC 和外部超时；
- 检查 SQL 是否失去索引或候选上限；
- 检查失败依赖是否快速退避而不是被每个请求重试。

## 20. 新成员第一天清单

- [ ] 确认 JDK/Maven/MySQL 环境；
- [ ] 创建个人 `application-local.yml`，无凭据进入 Git；
- [ ] `mvn clean test` 全部通过；
- [ ] 启动页面，完成一次联想、普通搜索和精确 ID 搜索；
- [ ] 在响应中找到 intent、products、trace、fromCache；
- [ ] 阅读自己岗位的代码入口和专项文档；
- [ ] 领取一个“两天内可演示”的首个任务；
- [ ] 写清输入、输出、依赖、测试数据、完成定义和风险；
- [ ] 与配对产品确认可判断对错的验收样本；
- [ ] 在开始破坏性接口修改前通知架构负责人。

## 21. 两周后的集成验收

必须能现场回答：

1. 当前请求走了哪条 NER/召回/缓存路径，为什么？
2. 模型比规则提升了哪些固定指标，在哪些困难样本仍失败？
3. ES 相比 MySQL 基线提升了哪些 Recall/NDCG，数据如何同步和回滚？
4. 规则变更是否降低业务约束违规，是否伤害相关性？
5. 冷/热缓存、峰值并发和依赖故障下的 P95/P99 是多少？
6. 当前版本如果出现错误，可以回退模型、规则、索引和缓存中的哪一层？

无法用数据和版本回答的问题，继续标记为“待验证”，不要用“基本完成”代替验收结论。

## 22. 详细资料索引

- 项目入口：`README.md`
- 团队执行：`docs/PROJECT_EXECUTION_AND_TEAM_PLAN.md`
- NER 全路线：`docs/NER_AND_SEARCH_DEVELOPMENT_GUIDE.md`
- 公司表适配：`docs/CDSGOODS_SEARCH_ADAPTER.md`
- 搜索联想：`docs/SEARCH_SUGGESTION_MODULE.md`
- ES mapping/同步边界：`es/README.md`
- 标注与评分命令：`ml/README.md`
- 联调数据格式：`integration-data/README.md`

遇到文档与代码不一致时，以冻结接口、自动化测试和当前配置为技术真相，同时在同一修改中修正文档。
