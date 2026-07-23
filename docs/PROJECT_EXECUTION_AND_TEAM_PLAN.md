# AI 商城搜索项目执行与团队分工方案

> 版本：V1.2
> 日期：2026-07-22
> 在线服务约束：JDK 8 不变
> 目标规模：约 500 万 SKU

## 1. 当前状态声明

当前仓库完成的是低延迟搜索 Demo 骨架，不是完整生产项目。

| 能力 | 状态 | 说明 |
|---|---|---|
| Java 8 搜索 API | Demo 已完成 | 可编译、可打包 |
| 规则/词典 NER | Demo 已完成 | 不是机器学习模型 |
| MySQL FULLTEXT 召回 | 公司表适配 V1 已完成 | 先 SPU 候选再关联 SKU/库存；待精确 DDL 和真实数据修正/压测 |
| Java 规则精排 | Demo 已完成 | 权重尚未用真实评测集调优 |
| Caffeine + 可选 Redis | Demo 已完成 | 未完成生产 Redis 集群治理 |
| 搜索阶段 trace | Demo 已完成 | 未接入完整监控平台 |
| 模型 NER 接入骨架 | Demo 已完成 | rule/shadow/hybrid/model、fixture、阈值和自动回退已实现 |
| MiniRBT/MacBERT NER 制品 | 未开始 | 无公司金标训练数据、无可用 ONNX 模型 |
| NER 标注与离线评分 | POC 已完成 | 有 Doccano 确定性分工、双标比较/仲裁转换、Label Studio 备选、校验、切分和 strict F1 脚本 |
| Elasticsearch | mapping + REST 召回 V1 已完成 | 一 SPU 文档 + nested SKU；同步 Worker 和真实集群联调未完成 |
| 搜索关键词联想 | 词典基线已完成 | 支持前/中/后缀与别名；热词/历史词/ES ngram Source 待实现 |
| 生产级商品同步 | 未开始 | 需要全量构建、增量 CDC 和对账 |
| 真实相关性评测 | 未开始 | 无金标 Query/商品 relevance judgment |
| 灰度、回滚、容量规划 | 未开始 | 需完成后才能上线 |

## 2. MiniRBT 到底已经训练好了什么

`hfl/minirbt-h256` 已经完成的是中文预训练和通用知识蒸馏：

- Transformer 结构已经确定；
- 中文 `vocab.txt` 已经确定；
- 模型已经学到通用中文上下文表示；
- 预训练权重可以直接下载；
- 不需要从随机参数开始训练。

它没有完成的是公司商城 NER：

- 没有 `CATEGORY/BRAND/PRODUCT_TYPE/SCENE/ATTRIBUTE_VALUE` 标签；
- 没有为这些标签训练 token classification head；
- 不知道公司的类目树、品牌别名和业务规则；
- 不知道“员工福利”“开专票”在公司系统中如何映射字段。

模型卡任务类型是 `Fill-Mask`，不是已经完成的商城 Token Classification。因此第一步是“基于预训练权重做领域微调”，不是训练大模型。

网上可能存在别人微调过的中文 NER 模型，但通常识别人名、地名、组织机构，或采用不同标签和不同数据。可以用来学习代码或做预标注对照，不应未经公司金标评测直接上线。

## 3. 是否一定要用公司商品数据库训练

不需要直接把 500 万商品记录当作 NER 训练集。

### 3.1 NER 真正需要的数据

NER 样本是：

```text
用户搜索 Query + 原文中的实体起止位置 + 实体类型
```

例如：

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

价格由确定性规则抽取，所以此例不需要把 `50元以内` 标成模型实体。

### 3.2 商品数据库的作用

商品库用于：

1. 建立标准类目、品牌、商品类型、颜色、材质和属性词典；
2. 建立别名到标准 ID 的映射；
3. 从商品组合生成弱标 Query；
4. 让离线大模型生成更自然的 Query 改写；
5. 构建 Elasticsearch 商品文档；
6. 验证 NER 实体是否真的能召回正确商品；
7. 建立相关性 judgment。

优先级是：真实历史 Query > 人工设计 Query > 商品库模板合成 Query > 大模型生成 Query。

## 4. 模型获得和首轮训练的具体步骤

### 4.1 固定模型版本

训练仓库新增：

```text
ml/
├── requirements.lock
├── configs/
├── data/
├── src/
├── tests/
└── artifacts/
```

通过 Hugging Face Transformers 下载：

```python
from transformers import AutoTokenizer, AutoModelForTokenClassification

MODEL_NAME = "hfl/minirbt-h256"

tokenizer = AutoTokenizer.from_pretrained(
    MODEL_NAME,
    revision="固定的模型 commit hash"
)

model = AutoModelForTokenClassification.from_pretrained(
    MODEL_NAME,
    revision="固定的模型 commit hash",
    num_labels=len(label2id),
    id2label=id2label,
    label2id=label2id
)
```

首次加载时 token classification head 会提示随机初始化，这是正常现象。预训练 Encoder 已有权重，只有商城标签分类头需要训练。

模型、tokenizer、commit hash 和许可证必须下载到公司制品库，生产环境不能依赖运行时访问 Hugging Face。

### 4.2 第一轮不要做蒸馏

首轮实验：

1. 当前规则 NER；
2. MiniRBT-H256 直接微调；
3. MacBERT-base 直接微调。

如果 MiniRBT 已经满足准确率和延迟，停止，不做蒸馏。

只有当 MacBERT 显著优于 MiniRBT，且 MiniRBT 没达到准入标准时，再执行 MacBERT → MiniRBT 下游任务蒸馏。

### 4.3 首轮 MiniRBT 参数

可以先固定一组基本参数跑通：

```yaml
model: hfl/minirbt-h256
task: token-classification
max_length: 64
learning_rate: 0.00008
train_batch_size: 32
eval_batch_size: 64
gradient_accumulation_steps: 1
epochs: 8
weight_decay: 0.01
warmup_ratio: 0.05
dropout: 0.1
fp16: true
seed: 42
early_stopping_patience: 2
metric_for_best_model: entity_f1
```

这是一组起跑参数，不是公司业务的最终最优参数。MiniRBT 官方经验指出，小模型通常需要比 base 模型更高的学习率和更多迭代轮数。

### 4.4 首轮调参矩阵

不要做无边界网格搜索。第一轮只测试：

| 参数 | 候选值 |
|---|---|
| learning rate | `5e-5`、`8e-5`、`1e-4` |
| epoch | 5、8、10 |
| max length | 32、64 |
| batch size | 在显存允许下 16、32、64 |
| dropout | 0.1、0.2 |
| random seed | 42、2026、3407 |

先固定除 learning rate 外的参数，找学习率；再固定学习率找 epoch/max length。不要一次把所有组合相乘。

优先级：

```text
修数据与标签规范
  > 调实体置信度
  > 调学习率/epoch
  > 调 batch/max length
  > 换模型结构
```

## 5. 训练集、验证集、测试集

### 5.1 三个集合的职责

| 集合 | 用途 | 是否参与调参 |
|---|---|---|
| train | 更新模型参数 | 是 |
| validation/dev | 选学习率、epoch、阈值和 checkpoint | 是 |
| test | 最终验收 | 否，只在版本冻结后运行 |

测试集一旦用于改参数，它就不再是测试集。

### 5.2 第一阶段规模

没有历史 Query 时，从小规模试点开始：

| 阶段 | train | dev | test | 目标 |
|---|---:|---:|---:|---|
| POC | 2,000 | 300 | 500 | 跑通训练与 Java 部署 |
| V1 | 10,000 | 2,000 | 3,000 | 第一版业务评测 |
| V2 | 20,000+ | 3,000 | 5,000 | 长尾和困难样本优化 |

数量不能替代覆盖度。测试集必须包含：

- 高频/中频/长尾 Query；
- 新品牌和新商品；
- 价格、多条件和否定表达；
- 中英文混合和型号；
- 零结果和非搜索 Query；
- 公司核心业务类目。

### 5.3 切分规则

推荐总体比例约为 80%/10%/10%，但要满足：

1. 按时间切分优先；
2. 同一规范化 Query 只能在一个集合；
3. 同一个模板生成的样本不能跨集合；
4. 同一用户会话尽量放在同一集合；
5. 保留 OOV 品牌/商品类型切片；
6. test 冻结并建立版本号。

### 5.4 数据校验

CI 中必须检查：

- `text[start:end] == entity.text`；
- span 不越界、不非法重叠；
- 标签属于固定集合；
- train/dev/test 无重复规范化 Query；
- 每类实体样本数和分布；
- 合成数据来源和模板 ID；
- PII 脱敏结果。

## 6. 评分标准

### 6.1 NER 严格实体评分

只有“起点、终点、标签”全部正确，实体才算正确。

```text
Precision = 正确预测实体数 / 所有预测实体数
Recall    = 正确预测实体数 / 所有真实实体数
F1        = 2 × Precision × Recall / (Precision + Recall)
```

必须报告：

- overall strict entity micro-F1；
- CATEGORY/BRAND/PRODUCT_TYPE/SCENE/ATTRIBUTE_VALUE 各自 P/R/F1；
- slot exact match：整条 Query 的所有槽位完全正确；
- OOV BRAND recall；
- 否定条件误判率；
- 模型低置信度和规则回退比例。

建议第一版目标：

| 指标 | 建议准入线 |
|---|---:|
| overall entity F1 | ≥ 93% |
| CATEGORY recall | ≥ 96% |
| BRAND recall | ≥ 96% |
| Student 相对 MacBERT F1 差距 | ≤ 1 个百分点 |
| INT8 相对 FP32 F1 下降 | ≤ 0.3 个百分点 |

最终阈值要根据公司金标数据难度调整。

### 6.2 路由评分

- EXACT_SKU accuracy；
- SEARCH/NON_SEARCH accuracy；
- 结构化硬过滤准确率；
- 低置信度路由准确率；
- 错误硬过滤导致零结果的比例。

### 6.3 召回和排序评分

NER 评分不能代替搜索评分：

| 指标 | 说明 |
|---|---|
| Recall@50/200 | 正确商品是否被召回 |
| MRR | 第一个正确商品出现的位置 |
| NDCG@10/20 | 多个相关商品的排序质量 |
| Zero Result Rate | 零结果率 |
| Constraint Violation | 超预算、无库存、属性冲突比例 |

每次 NER、ES mapping、召回规则、排序权重变化都必须跑同一套相关性回归。

### 6.4 性能评分

- NER P50/P95/P99；
- ES/DB 召回 P50/P95/P99；
- 规则引擎耗时；
- 缓存 L1/L2 命中率和延迟；
- 端到端搜索 P95/P99；
- CPU、内存、GC、线程数；
- 模型异常回退成功率。

## 7. 是否需要 Elasticsearch

结论：500 万 SKU 的生产搜索大概率需要 ES，但应当作为可替换召回通道接入，而不是让业务代码直接依赖 ES。

ES 主要解决：

- 中文全文检索和 BM25；
- 字段权重、短语、同义词、拼写和多字段召回；
- 类目、品牌、价格、标签的组合过滤；
- 聚合和筛选项；
- 高并发下稳定的有界候选召回；
- 后续向量或混合检索扩展。

MySQL 继续是商品事实源。ES 是可重建的搜索索引，不是库存和交易的唯一真相。

### 7.1 Java 8 约束下的接入建议

当前官方 Java API Client 要求较新的 Java 版本；旧 7.17 High Level REST Client 虽支持 Java 8 和 ES 8 compatibility mode，但官方已经标记 deprecated。

新项目不建议把已废弃 HLRC 作为长期架构。两种方案：

1. 主 Java 8 服务通过稳定的 HTTP/JSON `ElasticsearchGateway` 调用 ES REST API；
2. 如果公司允许辅助服务使用新 JDK，单独建设 Search Adapter 服务，内部使用官方新客户端，主商城 Java 8 服务只调用它。

在 Java 版本和公司部署规范明确前，只冻结 `ElasticsearchGateway` 接口，不在核心业务层绑定某个客户端。

### 7.2 第一版 ES 文档

建议字段：

```text
product_id          keyword
sku                 keyword
title               text + keyword/raw
brand_id            keyword
brand_name          text + keyword/raw
category_id         keyword
category_path       keyword[]
product_type        text + keyword/raw
scene_tags          keyword[] + text
attribute_tokens    text
price               scaled_float
stock_available     boolean
status              keyword
tenant_ids          keyword[]
featured            boolean
sales_score         rank_feature/number
updated_at          date
data_version        keyword
```

全文字段使用 `text`，ID、状态和精确过滤字段使用 `keyword`。同一字段需要全文和精确能力时使用 multi-fields。

中文分词至少对比：

- SmartCN；
- 公司词典增强方案；
- 业务允许的第三方中文 analyzer；
- 字符 ngram 基线。

不能只看分词示例，必须用 Recall/NDCG 选择。

### 7.3 商品同步

```text
MySQL 商品事实表
  ├─ 首次全量导出 → ES 新版本索引
  ├─ binlog/CDC 增量 → ES bulk upsert/delete
  ├─ 定期 checksum/数量对账
  └─ alias 原子切换 → 无停机发布新 mapping
```

同步团队必须处理：

- 乱序更新；
- 重复事件幂等；
- 删除和下架；
- 死信队列；
- 全量期间增量追平；
- 数据版本；
- MySQL/ES 数量与关键字段对账；
- 索引重建和回滚。

## 8. 完整在线框架

```text
SearchController
  → RequestValidator
  → QueryNormalizer
  → SearchCacheGateway
  → IntentRecognizer
       ├─ RuleBasedRecognizer
       ├─ OnnxNerRecognizer
       └─ HybridIntentRecognizer
  → QueryPlanner
  → RecallOrchestrator
       ├─ ExactSkuRecallChannel
       ├─ ElasticsearchRecallChannel
       ├─ MySqlFallbackRecallChannel
       └─ OptionalVectorRecallChannel
  → CandidateMerger / Deduplicator
  → ProductSnapshotBatchLoader
  → HardRuleFilter
  → RankingService
  → ResponseAssembler
  → CacheWriter
  → Metrics / Trace / FeedbackEvent
```

离线框架：

```text
商品同步：MySQL → full sync / CDC → ES
NER：Query → 标注 → 训练 → 评测 → ONNX → 模型仓库
相关性：Query judgment → Recall/NDCG 回归
缓存：热词分析 → 预热 → 失效/版本策略
反馈：曝光/点击/加购/购买 → 脱敏离线样本
```

## 9. 第一批必须冻结的接口

多人并行前先冻结接口，不要先冻结实现。

```java
public interface IntentRecognizer {
    IntentResult extract(String query);
}

public interface RecallChannel {
    RecallResult recall(QueryPlan plan);
    String name();
}

public interface ProductSnapshotRepository {
    Map<Long, ProductSnapshot> findAvailableByIds(Collection<Long> ids);
}

public interface SearchCacheGateway {
    Optional<SearchResponse> get(SearchCacheKey key);
    void put(SearchCacheKey key, SearchResponse response);
}

public interface RankingService {
    List<ScoredProduct> rank(QueryPlan plan, List<ProductSnapshot> candidates);
}

public interface FeedbackPublisher {
    void publish(SearchFeedbackEvent event);
}
```

还要冻结以下版本化对象：

- `NormalizedQuery`；
- `IntentResult`；
- `QueryPlan`；
- `RecallHit`；
- `ProductSnapshot`；
- `ScoredProduct`；
- `SearchTrace`；
- `SearchFeedbackEvent`；
- `SearchCacheKey`。

所有对象都要有 `schemaVersion/modelVersion/ruleVersion/indexVersion`。

## 10. 团队工作流拆分

### 工作流 A：架构与接口负责人

负责：

- 冻结领域对象和接口；
- 维护主 Pipeline；
- 定义 feature flag 和降级顺序；
- 管理跨模块代码评审；
- 维护 ADR（架构决策记录）。

交付：接口包、架构图、错误码、超时预算、集成测试骨架。

### 工作流 B：数据与标注团队

负责：

- Query 脱敏和采样；
- 标签规范；
- 标注平台和质检；
- train/dev/test 切分；
- 数据版本、重复和泄漏检测；
- hard slices。

交付：标注规范、数据集、质量报告、数据卡。

### 工作流 C：NER/模型团队

负责：

- MiniRBT/MacBERT 基线；
- 训练和调参；
- 实体置信度；
- 必要时知识蒸馏；
- ONNX/INT8；
- Python/Java parity；
- 模型卡和模型版本。

交付：ONNX 制品、vocab、labels、thresholds、metrics、checksum。

### 工作流 D：ES 与召回团队

负责：

- ES mapping/template/analyzer；
- BM25 和多字段召回；
- 类目、品牌、价格过滤；
- 同义词；
- candidate limit；
- Recall@K 回归；
- 索引 alias 与重建。

交付：ES index template、RecallChannel、召回评测报告。

### 工作流 E：商品同步团队

负责：

- 首次 500 万全量导入；
- CDC/binlog；
- bulk 写入；
- 幂等、乱序、删除；
- 对账、补偿、死信；
- 索引切换。

交付：同步 Worker、对账工具、重建与回滚手册。

### 工作流 F：规则与排序团队

负责：

- 硬规则定义；
- 规则配置版本；
- 候选合并和去重；
- BM25、业务分、预算分等排序特征；
- NDCG/MRR 调优；
- 运营置顶边界。

交付：规则引擎、排序配置、相关性回归报告。

### 工作流 G：缓存与性能团队

负责：

- Caffeine L1；
- Redis Cluster/Sentinel 选型；
- Query→Intent、Query→Result 缓存；
- TTL 抖动、负缓存、热 key；
- key 版本和租户隔离；
- 超时、熔断、故障退避；
- 缓存预热；
- 压测和容量规划。

交付：SearchCacheGateway、Redis 运维配置、压测报告、故障演练。

### 工作流 H：评测与 QA 团队

负责：

- NER strict F1；
- Recall@K/NDCG/MRR；
- API 契约测试；
- golden Query；
- 性能和稳定性测试；
- 模型、规则、索引版本回归；
- Shadow/Canary 验收。

交付：统一评测工具、冻结测试集、发布准入报告。

### 工作流 I：平台、监控与安全团队

负责：

- CI/CD；
- 模型和索引制品仓库；
- 指标、日志、链路追踪；
- 告警；
- 密钥、TLS、ES/Redis 权限；
- 脱敏和审计；
- 灰度、配置中心和回滚。

交付：Dashboard、告警、部署清单、上线/回滚手册。

### 工作流 J：反馈与实验团队（可后置）

负责：

- 曝光、点击、加购、购买事件；
- Query reformulation；
- A/B 实验；
- 主动学习困难样本；
- Learning-to-Rank 数据。

交付：反馈事件协议、实验报告、训练样本回流。

## 11. 人数不足时如何合并

### 4～5 人团队

| 成员 | 工作 |
|---|---|
| 1 | 架构、Pipeline、Java 集成 |
| 2 | 数据标注 + NER 模型 |
| 3 | ES 召回 + 商品同步 |
| 4 | 规则排序 + 相关性评测 |
| 5 | Redis、性能、DevOps、监控 |

### 8～10 人团队

按上一节 A～J 独立工作流拆分，NER、ES/同步、缓存/性能至少各有独立负责人。

## 12. 依赖关系与并行顺序

```text
公司 Schema/样例数据
  → 接口与领域对象 V1 冻结
      ├─ 数据/标注 ─→ NER 训练 ─→ ONNX
      ├─ ES mapping ─→ 全量/CDC ─→ ES Recall
      ├─ 规则定义 ─→ Rule Engine
      ├─ Redis 规范 ─→ Cache Gateway
      └─ 评测集 ─→ 统一回归工具

上述工作完成
  → Pipeline 集成
  → 500 万压测
  → Shadow
  → Canary
  → 上线
```

不能等待模型训练完成后才开始 ES、缓存、规则和评测。它们可以在接口冻结后并行开发。

## 13. 前两周具体安排

### 第 1～2 天：信息收集

- [x] 公司核心表关系、字段用途和行数已初步梳理；精确文本 DDL/现有索引仍待补；
- [ ] 1,000～5,000 条脱敏商品；
- [ ] 500～2,000 条脱敏 Query；
- [ ] 50～200 条人工期望结果；
- [ ] QPS、SLA、服务器和中间件约束；
- [ ] 明确 Java 8 约束是仅主服务还是所有辅助服务。

### 第 3～4 天：接口冻结

- [ ] `IntentResult` V1；
- [ ] `QueryPlan` V1；
- [ ] `RecallChannel` V1；
- [ ] `ProductSnapshot` V1；
- [ ] `SearchCacheKey` V1；
- [ ] `SearchTrace` V1；
- [ ] 超时和降级顺序；
- [ ] ADR：ES 接入方式。

### 第 5 天：骨架验收

- [ ] Rule/NER mock 可切换；
- [ ] MySQL/ES mock Recall 可切换；
- [ ] Redis 可关闭；
- [ ] 各模块失败能降级；
- [ ] API contract test 通过；
- [ ] 每阶段 metrics 存在。

### 第 2 周：各团队并行

- 模型团队：完成 2,000 条 POC 标注和 MiniRBT 首次训练；
- ES 团队：完成 index template、全量 1 万样本和基础 BM25；
- 同步团队：完成 bulk/幂等/删除骨架；
- 规则团队：整理硬规则和 100 条 golden Query；
- 缓存团队：完成 key、TTL、租户、版本和故障策略；
- QA 团队：统一 NER/Recall/NDCG/性能报告格式；
- 平台团队：准备开发环境、制品库和 Dashboard。

## 14. 框架完成的验收标准

“框架搭建完成”不是所有算法完成，而是：

- [x] `Product`（SPU + matched SKU）、`IntentResult`、`ProductCatalogRepository` 和 `RecallChannel` V1 已落地；
- [x] Rule/模型 NER 可配置切换，未交付 ONNX 时使用 fixture/stub；
- [x] MySQL/ES 召回可插拔，ES REST 异常自动回退 MySQL；向量召回按评测结果后置；
- [ ] 候选合并、批量商品加载、硬过滤和排序链路完整；
- [ ] L1/L2 缓存通过统一接口；
- [x] 模型、规则、缓存、索引的配置版本号已进入 trace/cache key；
- [ ] 各模块有超时、熔断和回退；
- [ ] 全链路 trace 和指标存在；
- [ ] 数据同步有全量、增量、删除、对账接口；
- [ ] 统一离线评测可以跑规则/模型/ES 多版本；
- [ ] CI 能运行 contract、unit、integration 和 regression tests；
- [x] NER 未完成实现已有 mock/stub；ES 已有真实 REST 通道，不阻塞同步团队。

## 15. 当前最先做的五件事

1. 获取公司脱敏 Schema、样例商品、Query 和业务规则；
2. 冻结接口与领域对象，不先绑定 ES 客户端或具体 NER 实现；
3. 建立 100～200 条 golden Query 和当前规则基线；
4. 并行启动 MiniRBT POC、ES mapping、Redis 规范和规则整理；
5. 第 2 周末进行第一次全链路集成，模型和 ES 可以先用 mock，但契约必须真实。

## 16. 官方参考资料

- [MiniRBT-H256 模型卡](https://huggingface.co/hfl/minirbt-h256)
- [MiniRBT 官方仓库与参数建议](https://github.com/iflytek/MiniRBT)
- [Hugging Face Token Classification](https://huggingface.co/docs/transformers/tasks/token_classification)
- [ONNX Runtime Java](https://onnxruntime.ai/docs/get-started/with-java.html)
- [Elasticsearch Java 客户端当前要求](https://www.elastic.co/docs/reference/elasticsearch/clients/java/getting-started)
- [旧 7.17 HLRC Java 8 与 ES 8 compatibility mode](https://www.elastic.co/guide/en/elasticsearch/client/java-rest/current/java-rest-high-compatibility.html)
- [Elasticsearch Smart Chinese Analyzer](https://www.elastic.co/docs/reference/elasticsearch/plugins/analysis-smartcn)
- [Elasticsearch text/keyword multi-fields](https://www.elastic.co/docs/reference/elasticsearch/mapping-reference/multi-fields)
- [Elasticsearch alias 无停机重建](https://www.elastic.co/guide/en/elasticsearch/reference/current/aliases.html)
