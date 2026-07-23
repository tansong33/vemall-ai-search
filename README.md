# AI 商城低延迟搜索 Demo

JDK 8 · Spring Boot 2.7.18 · MyBatis-Plus · MySQL 8 · Elasticsearch REST · Caffeine · 可选 Redis

## 项目当前定位

这是一个可以启动、测试、分工开发的完整 POC 框架，不是已经满足生产上线条件的成品：

- 已完成：低延迟规则主链路、关键词前/中/后缀联想、demo/cdsgoods 双数据适配、SPU+最佳 SKU 领域模型、MySQL 有界召回、ES mapping/REST 召回、规则精排、L1/L2 缓存、自动降级、标注与 NER 评测工具；
- 待真实数据后完成：精确 DDL 差异修正、商品全量/CDC 同步 Worker、MiniRBT 微调和 ONNX 推理、相关性金标、682 万 SKU 联调压测、生产监控与灰度发布。

多人开发时先冻结 `IntentResult`、`NerModelClient`、`RecallChannel` 和 API 出参；各组通过 stub/fixture 联调，不应互相等待实现完成。

这个版本把生成式大模型和在线 Embedding 从商品搜索主链路移除：

```text
请求
  → L1/L2 热词缓存
  → 规则/词典 NER（类目、品牌、场景、价格、容量、B 端条件）
  → 级联路由
      ├─ 明确 SPU/SKU/条码：MySQL 精确直查
      └─ 普通需求：ES 主召回，或 MySQL ngram FULLTEXT 有界兜底
  → Java 规则引擎（硬过滤 + 软打分）
  → 直接返回商品卡
```

在线链路没有 LLM、Embedding API、向量库和话术生成。大模型仍适合离线做 query 标注、同义词扩充、训练数据生成和蒸馏，但不阻塞商品结果。

后续从 NER 标签设计、Teacher/Student 选型、蒸馏、ONNX/Java 8 接入到灰度上线的完整路线见 [`docs/NER_AND_SEARCH_DEVELOPMENT_GUIDE.md`](docs/NER_AND_SEARCH_DEVELOPMENT_GUIDE.md)。

多人协作的接口冻结、ES/缓存/模型/同步/规则/评测工作流和前两周计划见 [`docs/PROJECT_EXECUTION_AND_TEAM_PLAN.md`](docs/PROJECT_EXECUTION_AND_TEAM_PLAN.md)。

会议讲解与岗位认领使用 [`docs/MEETING_PROJECT_FLOW_AND_ROLES.md`](docs/MEETING_PROJECT_FLOW_AND_ROLES.md)；开发人员使用 [`docs/DEVELOPER_LOCAL_GUIDE.md`](docs/DEVELOPER_LOCAL_GUIDE.md)；不方便浏览 GitHub 的 3 名产品可直接分发 [`AI商城智能搜索-产品团队工作手册.docx`](docs/deliverables/AI商城智能搜索-产品团队工作手册.docx)。Word 文档可通过 `tools/build_team_documents.ps1` 从 Markdown 源稿重新生成。

可直接运行的标注准备、Gold/Silver 数据规范、数据校验/切分和 strict entity F1 评分工具见 [`ml/README.md`](ml/README.md)；Doccano 的 8 人任务拆分、双标冲突比较和仲裁步骤见 [`ml/annotation/DOCCANO_WORKFLOW.md`](ml/annotation/DOCCANO_WORKFLOW.md)。

### 团队建议阅读顺序

1. 本 README：运行项目并理解在线主链路；
2. [`docs/DEVELOPER_LOCAL_GUIDE.md`](docs/DEVELOPER_LOCAL_GUIDE.md)：按岗位找到代码入口、首个任务和验收证据；
3. `RecommendPipeline`：理解一次请求如何经过缓存、NER、召回、规则和组装；
4. `RuleBasedNerService`、`HybridIntentRecognizer`：理解规则基线、模型 shadow 和降级；
5. `ProductSearchService`、`RecallOrchestrator`：理解 MySQL 当前实现和 ES 扩展点；
6. [`ml/README.md`](ml/README.md)：跑一遍 Doccano 分配、冲突比较、数据校验和 F1 评分；
7. [`docs/PROJECT_EXECUTION_AND_TEAM_PLAN.md`](docs/PROJECT_EXECUTION_AND_TEAM_PLAN.md)：按负责人领取模块；
8. [`docs/NER_AND_SEARCH_DEVELOPMENT_GUIDE.md`](docs/NER_AND_SEARCH_DEVELOPMENT_GUIDE.md)：进入模型训练、ONNX 和上线阶段。

## 对原项目的取舍

保留：

- JDK 8、Spring Boot 2.7、MyBatis-Plus、MySQL、Redis 依赖和已有 API 外形；
- `Product`、请求/响应 DTO、Controller 分层；
- “召回后再按业务规则排序”的思想；
- 缓存和可配置权重。

移除：

- 每次请求调用 LLM 做意图 JSON；
- 每次请求调用在线 Embedding；
- 500 万向量全部装进 JVM 后逐条计算余弦；
- 每个关键词一次 `LIKE '%词%'` 全表查询；
- 每个向量命中再 `selectById` 的 N+1 查询；
- 商品出来前同步调用第二次 LLM 生成话术；
- `/api/products` 无分页读取全表。

原向量实现适合几千到两万条 POC，不能平移到 500 万 SKU。按 1024 维 float32 粗算，仅原始向量就约 19 GiB，尚未包含 Java 对象、Map 和堆索引开销；暴力扫描也会让 CPU 成本随 SKU 线性增长。

## 目录

```text
src/main/java/cn/vetech/aimall/
├── controller/                 HTTP 接口
├── mapper/
│   ├── ProductMapper.java      demo 表访问
│   ├── CdsgoodsProductMapper.java
│   └── *SqlProvider.java       参数化有界 SQL
├── model/
│   ├── dto/                    意图、商品卡、trace
│   ├── entity/Product.java     SPU + 最佳匹配 SKU 统一对象
│   └── search/SearchCriteria.java
├── repository/                 demo/cdsgoods 物理表适配层
└── service/
    ├── ner/
    │   ├── EntityDictionaryService.java  类目/品牌内存词典
    │   ├── RuleBasedNerService.java      正则 + 词典 NER
    │   ├── HybridIntentRecognizer.java   rule/shadow/hybrid/model 切换与降级
    │   └── NerModelClient.java           ONNX/fixture 统一模型端口
    ├── suggestion/                       可插拔关键词联想源与聚合服务
    ├── ProductSearchService.java         级联路由
    ├── recall/                           MySQL/ES 可插拔召回与自动回退
    ├── ProductRuleEngine.java            硬过滤 + 软打分
    ├── SearchCacheService.java           Caffeine L1 + 可选 Redis L2
    ├── ResponseAssembler.java            非生成式结果组装
    └── RecommendPipeline.java            主链路与阶段计时
```

公司库字段映射、SQL 执行路径和联调验收见 [`docs/CDSGOODS_SEARCH_ADAPTER.md`](docs/CDSGOODS_SEARCH_ADAPTER.md)；ES 模板、虚构文档和同步边界见 [`es/README.md`](es/README.md)。

## 初始化和启动

要求 JDK 8、Maven 3.8+、MySQL 8。编译目标始终是 Java 8；更高版本 JDK 也可以执行 Maven 构建。

1. 导入样例商品：

```bash
mysql -u root -p < sql/product.sql
```

2. 为已有商品表执行搜索索引迁移：

```bash
mysql -u root -p < sql/search_optimization.sql
```

`FULLTEXT ... WITH PARSER ngram` 是中文数据库召回的关键。500 万行生产库不要在流量高峰直接建索引，应使用业务已有的在线 DDL/影子表流程。迁移中的 `EXPLAIN ANALYZE` 用于确认真实热词没有无界全表扫描。

连接公司 `cdsgoods` 时不要执行 demo 脚本，先由 DBA 审核 [`sql/cdsgoods_search_indexes.sql`](sql/cdsgoods_search_indexes.sql)，并在本地配置设置：

```yaml
aimall:
  search:
    data-source: cdsgoods
    require-tenant-context: true
```

3. 创建本地配置：

```powershell
Copy-Item src/main/resources/application-local-template.yml src/main/resources/application-local.yml
```

填入 MySQL 用户名和密码。Redis 默认关闭；单机 Demo 使用 Caffeine L1，无 Redis 也不会产生连接等待。多实例部署时配置 Redis 后设置：

```yaml
aimall:
  cache:
    redis-enabled: true
```

4. 构建和启动：

```bash
mvn clean test
mvn spring-boot:run
```

浏览器访问 `http://localhost:8080/`。

### 在真实模型到位前验证模型链路

默认配置为 `mode=rule`、`model-provider=stub`。如果只想验证 shadow/融合、阈值和降级链路，可在本地配置中使用：

```yaml
aimall:
  ner:
    mode: shadow        # shadow 不改变搜索结果；联调后才改 hybrid
    model-provider: fixture
    model-version: fixture-v1
    shadow-sample-rate: 1.0
```

fixture 是词条模拟器，不是机器学习模型，不能用于汇报准确率。查看实际状态：`GET /api/admin/ner/status`。未经业务数据微调的 MiniRBT 没有公司标签对应的分类头，因此不直接放进在线主链路。

## API

### 搜索关键词联想

```text
GET /api/search/suggestions?q=魔师&limit=8
```

当前使用约 3.3 万条类目/品牌内存词典，支持规范名和别名的完全、前缀、中间及后缀匹配；例如 `therm` 或 `魔师` 都可以联想到规范品牌名。Demo 输入框已经加入 160ms 防抖、请求取消和键盘选择。生产 ES 联想索引方案见 [`docs/SEARCH_SUGGESTION_MODULE.md`](docs/SEARCH_SUGGESTION_MODULE.md)。

### 搜索

```bash
curl -X POST http://localhost:8080/api/recommend \
  -H "Content-Type: application/json" \
  -d '{"tenantCode":"TENANT-001","channelCode":"RETAIL","query":"夏天办公室降暑的员工福利，预算50元以内，要现货"}'
```

响应包含：

- `intent`：NER 抽出的商品 ID、类目、品牌、价格、场景和属性；
- `products`：数据库真实商品，经规则引擎排序；
- `trace`：NER、数据库、规则和总耗时，以及实际路由；
- `fromCache`：是否命中 L1/L2 热词缓存。

显式 SPU ID、SKU ID、条码或供应商 SKU ID 会走 MySQL 精确查询，例如：

```json
{"query":"商品编号 123"}
```

### 分页商品列表

```text
GET /api/products?afterId=&size=20
```

这是按 varchar 主键游标翻页；下一页把本页最后一个 `id` 作为 `afterId`。接口只用于联调抽样，不用作 682 万 SKU 的全量导出器。

### 刷新 NER 词典

```text
POST /api/admin/ner-dictionary/refresh
```

应用启动完成后会从数据库加载 distinct 类目/品牌到内存，之后定时刷新。在线实体匹配按 query 子串查 HashSet，不会逐个遍历全部品牌。

## 规则行为

当前 NER 能识别：

- 类目别名：保温杯/运动水壶 → 水杯，风扇/加湿器 → 小家电等；
- 数据库中已有的类目和品牌；
- 场景：员工福利、送礼、办公、差旅、户外、节日、劳保等；
- 价格：`50以内`、`预算 50`、`50-100 元`、`100 元以上`；
- 属性：容量、颜色、材质、现货、积分购买、可开专票、可定制 Logo；
- 显式商品 ID。

库存、价格、积分、专票、Logo、现货是硬规则；类目、品牌、场景、普通属性、运营主推和预算贴合度参与软打分。所有权重在 `application.yml` 中配置。

## 500 万 SKU 上线边界

这个 Demo 验证的是“无在线大模型的低延迟主链路”，不是最终搜索平台。上线前至少要完成：

1. 用真实 5 百万商品和真实 query 日志跑 `EXPLAIN ANALYZE`、P95/P99 和并发压测；
2. 商品表增加稳定的 `sku` 唯一索引，货号路由应查 SKU，不要复用自增 ID；
3. 高频更新的库存/上下架状态与搜索文档建立可靠同步，缓存 key 带租户、渠道、用户价格体系和规则版本；
4. 高基数属性不要长期放在 JSON 字符串里做 contains，应建设可索引的属性倒排表或搜索引擎字段；
5. ES 主召回通过 `RecallChannel` REST 实现；索引同步、中文 analyzer 和分片数必须用真实数据完成容量/相关性验证；
6. 用离线大模型标注历史 query，蒸馏/训练轻量 BERT NER + 意图分类器，通过 ONNX Runtime 在 Java 8 服务内推理；规则 NER继续作为兜底；
7. 导购文案如需大模型，使用独立 SSE/异步旁路，不能阻塞商品列表接口。

不要把 NER 理解成向量检索的等价替代。NER 擅长把明确条件变成可索引过滤；语义召回擅长解决同义表达和长尾概念。当后续离线评测证明规则 + FULLTEXT 的召回率不足时，可增加独立 ANN 召回通道，但应使用真正的向量检索服务，不能恢复 JVM 全量暴力扫描。
