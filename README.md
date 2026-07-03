# AI 智能商城 Demo（ai-mall-demo）

> 多模态意图理解 + 混合召回 + 规则重排 + 推荐生成 的**第一版可运行 Demo**。
> 用户通过**文字（可附图片）**描述需求，系统理解意图并从商品库中推荐合适商品、给出推荐理由，必要时反问澄清。
>
> 设计目标：**功能做到 60 分，架构做到 90 分** —— 每个 AI 能力都是"接口 + 多实现"的可插拔设计，
> 默认 **mock 模式零外部依赖即可完整跑通全链路**，配一个 API Key 即切换真实大模型，代码零改动。

---

## 1. 功能一览

| 能力 | 状态 | 说明 |
|---|---|---|
| 文字意图理解（类目/预算/场景/关键词/澄清项） | ✅ | mock=规则词典；真实模式=LLM 严格 JSON 输出 + 解析失败自动降级规则 |
| 图片理解（图 → 商品属性描述） | ✅ 框架就绪 | 真实模式走 VLM（OpenAI vision 协议）；mock 模式提示切换 |
| 语义召回（向量检索） | ✅ | mock=bigram 哈希向量；真实模式=商用/开源 embedding，代码不变 |
| 关键词召回 | ✅ | MySQL LIKE 兜底品牌/精确词（可平替 ES/BM25） |
| 结构化硬过滤 | ✅ | 预算上限 / 库存>0 / 类目，**从机制上杜绝推超预算和无货商品** |
| 规则重排（可配权重） | ✅ | 语义分+关键词分+主推+预算贴合，权重全在 yml |
| 推荐话术生成 + 反问澄清 | ✅ | 真实模式=LLM（prompt 强约束只推真实商品）；mock=模板 |
| Redis 查询缓存 | ✅ | 相同问题直接返回，**模型调用成本归零**；Redis 挂了自动跳过不影响主流程 |
| Web 对话演示页 | ✅ | 支持图片上传、商品卡渲染、意图/得分调试信息 |
| 类目过严自动放宽重试 | ✅ | 按类目召回为空时去掉类目约束重召一次 |

## 2. 整体架构

```
                       ┌──────────────────────────── 离线（启动时构建）────────────────────────────┐
                       │  MySQL product 表 ──► toEmbeddingText() ──► EmbeddingClient ──► VectorStore │
                       └──────────────────────────────────────────────────────────────────────────┘
                                                            │ 供数
  用户(文字/图片)                                            ▼
      │        ┌─────────────┐   ┌──────────────────────────────────┐   ┌────────────┐   ┌────────────┐
      ├──────► │ 意图理解     │──►│ 混合召回                          │──►│ 精排        │──►│ 生成        │──► 话术+商品卡
      │        │IntentService│   │HybridRecallService               │   │RerankService│  │Generation  │
      │        │ LLM/VLM     │   │ ├ SemanticRecallChannel(向量)     │   │ 可配权重     │  │Service     │
      │        │ ↓失败降级    │   │ ├ KeywordRecallChannel(LIKE)     │   └────────────┘  │ LLM/模板    │
      │        │ 规则词典     │   │ └ + 硬过滤(预算/库存/类目)         │                    └────────────┘
      │        └─────────────┘   └──────────────────────────────────┘
      │                                    ▲
      └── Redis 缓存(RecommendPipeline)：命中直接返回 ──┘        全流程编排：RecommendPipeline
```

分层与代码的对应关系（每层一个独立 Service，便于**按层归因**做迭代调优）：

| 链路环节 | 类 | 可插拔扩展点 |
|---|---|---|
| LLM 出口 | `llm/LlmClient` | #1 新增实现类切换任意厂商 |
| 向量化 | `embedding/EmbeddingClient` | #2 mock / OpenAI 兼容 / 自托管 BGE |
| 向量库 | `vectorstore/VectorStore` | #3 内存 / Milvus / pgvector |
| 召回通道 | `service/recall/RecallChannel` | #4 实现接口注册为 Bean 即自动接入（如以图搜图、协同过滤） |
| 精排 | `service/RerankService` | #5 yml 调权重；可加 cross-encoder / 个性化信号 |
| 编排 | `service/RecommendPipeline` | 缓存→意图→召回→精排→生成 |

## 3. 技术栈

- **JDK 8**（编译目标 1.8）
- **Spring Boot 2.7.18**（最后一个官方支持 JDK8 的主线版本）
- Spring Web / Spring Data JPA / Spring Data Redis / Bean Validation
- **MySQL 8**（商品库）、**Redis**（查询缓存）
- Lombok、Jackson（随 Boot 内置）
- 外部大模型：任意 **OpenAI 兼容协议** 服务（通义千问百炼 / 豆包方舟 / DeepSeek / 智谱 / GPT 等）

## 4. 快速开始

### 4.1 前置条件
- JDK 8、Maven 3.6+
- Docker（推荐，用于一键起 MySQL/Redis）；或本机自装 MySQL 8 + Redis

### 4.2 三步跑起来（推荐路径）

```bash
# ① 起依赖：MySQL(自动执行 sql/init.sql 建库建表+24条样例商品) + Redis
docker compose up -d

# ② 启动应用（默认 mock 模式，不需要任何 API Key）
mvn spring-boot:run

# ③ 打开浏览器
#    http://localhost:8080        —— 对话演示页
```

启动日志中看到 `商品向量索引构建完成：24 条` 即链路就绪。

### 4.3 不用 Docker 的手工方式
1. 本机 MySQL 执行 `sql/init.sql`（会创建库 `ai_mall`、用户 `aimall/aimall123`、表和数据）。
2. 本机启动 Redis（默认 6379 无密码）。
3. 若连接信息不同，改 `application.yml` 的 `spring.datasource` / `spring.redis`。
4. `mvn spring-boot:run`。

### 4.4 验证用例（curl）

```bash
# 场景+预算：夏季降暑福利，预算50
curl -s -X POST http://localhost:8080/api/recommend \
  -H 'Content-Type: application/json' \
  -d '{"query":"想给团队买点夏天降暑的福利，预算50一个人"}'

# 类目+送礼：茶叶礼盒
curl -s -X POST http://localhost:8080/api/recommend \
  -H 'Content-Type: application/json' \
  -d '{"query":"有什么送客户的茶叶礼盒"}'

# 预算过滤：100元以内数码
curl -s -X POST http://localhost:8080/api/recommend \
  -H 'Content-Type: application/json' \
  -d '{"query":"100元以内的数码产品"}'

# 再发一次同样请求 → 响应里 fromCache=true（Redis 缓存生效）

# 商品全量 / 重建向量索引
curl -s http://localhost:8080/api/products
curl -s -X POST http://localhost:8080/api/admin/reindex
```

## 5. 切换真实大模型（一处配置，代码零改动）

以**阿里云百炼（通义千问）**为例，编辑 `application.yml`：

```yaml
aimall:
  llm:
    provider: openai                 # mock -> openai
    base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
    api-key: ${AIMALL_LLM_API_KEY}   # 环境变量注入，别把 Key 提交进仓库
    chat-model: qwen-plus
    vision-model: qwen-vl-plus       # 配了它，图片理解(以图找同类)即生效
  embedding:
    provider: openai
    base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
    api-key: ${AIMALL_EMBEDDING_API_KEY}
    model: text-embedding-v3
```

```bash
export AIMALL_LLM_API_KEY=sk-xxxx
export AIMALL_EMBEDDING_API_KEY=sk-xxxx
mvn spring-boot:run
```

其他厂商同理，只换 `base-url` + 模型名（都走 OpenAI 兼容协议）：

| 厂商 | base-url 示例 | 说明 |
|---|---|---|
| 豆包（火山方舟） | `https://ark.cn-beijing.volces.com/api/v3` | 有免费额度，适合开发调试 |
| DeepSeek | `https://api.deepseek.com/v1` | 无多模态，仅 chat-model |
| 智谱 | `https://open.bigmodel.cn/api/paas/v4` | GLM-4V 支持视觉 |
| OpenAI | `https://api.openai.com/v1` | gpt-4o 系列，成本较高 |

> 稳健性设计：真实模型调用失败 / 输出 JSON 解析失败时，意图层自动降级到规则词典、
> 生成层自动降级到模板话术 —— **链路永不中断**，这对演示与生产都很重要。

## 6. API 文档

### POST `/api/recommend`（JSON）
```json
{
  "sessionId": "可选，预留多轮对话",
  "query": "想给团队买夏天降暑的福利，预算50一个人",
  "imageBase64": "可选，图片base64（不含 data: 前缀）",
  "imageMimeType": "image/jpeg"
}
```

### POST `/api/recommend/upload`（multipart，前端页面使用）
字段：`query`（文本）、`image`（文件，可选）

### 响应（两个接口一致）
```json
{
  "reply": "面向用户的导购话术（含推荐理由/反问）",
  "products": [
    { "id": 4, "title": "桌面USB静音小风扇", "price": 45.00, "sceneTags": "夏季,办公,员工福利,降暑",
      "stock": 600, "pointsEligible": true, "score": 0.83, "reason": "契合「夏季」场景，三档风力静音设计" }
  ],
  "intent": { "category": null, "budgetMax": 50, "scenes": ["夏季","员工福利"], "keywords": ["降暑","福利"], "clarifications": ["团队人数"] },
  "needClarification": true,
  "fromCache": false
}
```

### GET `/api/products` —— 商品全量（调试）
### POST `/api/admin/reindex` —— 重建向量索引（商品上下架/改描述后调用）

## 7. 可扩展性设计（为什么说架构 90 分）

本 Demo 的每个"将来一定会换"的组件都收敛到了接口后面：

1. **换大模型厂商**：新增 `LlmClient` 实现（或直接复用 OpenAI 兼容实现改 base-url）→ 改 yml。
2. **换/升级 embedding**：`EmbeddingClient` 新实现（如自托管 BGE 的 HTTP 服务）→ 改 yml。向量质量提升，检索代码不变。
3. **换向量库**：SKU 到十万级后实现 `VectorStore` 的 Milvus/pgvector 版本，`InMemoryVectorStore` 直接退役。
4. **加召回通道**：实现 `RecallChannel` 注册为 Bean **即自动并入混合召回**（Spring 注入 `List<RecallChannel>`）。
   计划中的以图搜图（CLIP 图像向量）、协同过滤、运营置顶位，都从这里进。
5. **调排序策略**：重排权重全部在 yml（`aimall.rerank.*`），业务对齐"什么排前面"不用发版。
   接 BGE-reranker / LLM 打分 / 用户个性化分，在 `RerankService` 追加信号项即可。
6. **成本控制**：Redis 查询缓存 TTL 可配；后续可加"简单查询路由便宜模型"的 Router（在 `LlmClientFactory` 扩展）。
7. **多轮对话/个性化**：`RecommendRequest.sessionId` 已预留，会话历史与用户画像从这里挂载。
8. **评测**：链路每层独立 Service，评测脚本可分别打点意图准确率 / Recall@K / 排序 NDCG（对应项目方案 P4 阶段）。

## 8. 关键设计决策（Demo 虽小，原则拉满）

- **RAG 防幻觉**：生成层 prompt 强约束"只能推荐给定列表中的商品"，且商品卡直接由召回结果构造，
  **模型只负责说话，不负责决定推荐哪个商品** —— 电商场景最致命的"编造商品"从机制上被排除。
- **硬过滤是保险丝**：预算/库存/类目在召回后立即硬过滤，即使语义召回"觉得很像"也推不出超预算/无货商品。
- **双路径永不中断**：每个 AI 环节都有"真实模型 + 规则/模板降级"两条路径，接口超时/输出不合法都不会 500。
- **Mock 与真实同构**：mock 模式与真实模式走完全相同的代码路径和数据结构，
  切换模型只是"向量和话术变好"，团队可以先在 mock 上把工程、评测、前端全部做完。
- **缓存与观测**：响应带 `fromCache` 标记与各层日志（召回条数/过滤原因/耗时），为成本与效果观测打底。

## 9. 当前 Demo 的已知边界（后续迭代项）

| 边界 | 现状 | 升级方向（对应项目排期） |
|---|---|---|
| mock 向量无真实语义 | bigram 哈希只有字面相似 | P2：切 BGE/商用 embedding（改 yml 即可） |
| 图片理解 mock 下不可用 | 返回占位提示 | P2：配置 vision-model 即生效；以图搜图加 CLIP 召回通道 |
| 分词简陋 | 停用词+标点切分 | 引入 HanLP/jieba 或交给 LLM |
| 关键词召回用 LIKE | 万级 SKU 够用 | 数据量大后换 Elasticsearch（BM25），实现 RecallChannel 即可 |
| 无多轮对话记忆 | sessionId 已预留 | P3：Redis 存会话意图，做增量槽位合并 |
| 无个性化 | — | 行为数据积累后在 Rerank 加用户偏好信号 |
| 无评测脚本 | — | P4：按方案建评测集 + 自动跑分 |
| 类目词典人工维护 | 24 商品够用 | 类目归一化交给 LLM，或从商品表自动构建 |

## 10. 目录结构

```
ai-mall-demo/
├── pom.xml                          # JDK8 + Spring Boot 2.7.18
├── docker-compose.yml               # MySQL(自动初始化) + Redis
├── sql/init.sql                     # 建库建表 + 24 条福利商城样例商品
└── src/main/
    ├── resources/
    │   ├── application.yml          # 所有可插拔配置（模型/召回/权重/缓存）
    │   └── static/index.html        # 对话演示页（图片上传/商品卡/意图调试）
    └── java/com/vetech/aimall/
        ├── AiMallApplication.java
        ├── config/                  # AiMallProperties(配置总线) / RestTemplate
        ├── controller/              # /api/recommend, /api/products, /api/admin/reindex
        ├── model/entity/Product.java
        ├── model/dto/               # IntentResult / RecommendRequest|Response / ProductCard / ScoredProduct
        ├── repository/              # ProductRepository(JPA + LIKE 召回)
        ├── llm/                     # LlmClient 接口 + Mock + OpenAI兼容 + 工厂    ← 扩展点#1
        ├── embedding/               # EmbeddingClient 接口 + Mock + OpenAI兼容     ← 扩展点#2
        ├── vectorstore/             # VectorStore 接口 + 内存实现                  ← 扩展点#3
        └── service/
            ├── IntentService.java          # 意图理解（LLM↔规则 双路径）
            ├── recall/                     # RecallChannel SPI + 语义/关键词 + 混合编排 ← 扩展点#4
            ├── RerankService.java          # 精排（yml 权重）                      ← 扩展点#5
            ├── GenerationService.java      # 话术生成（LLM↔模板 双路径，防幻觉约束）
            ├── RecommendPipeline.java      # 主编排 + Redis 缓存
            └── VectorIndexService.java     # 启动时构建商品向量索引
```

## 11. 常见问题

**Q：启动报数据库连接失败？**
确认 `docker compose up -d` 后 MySQL 已就绪（首次初始化约需 20–30 秒），或检查 `spring.datasource` 配置。

**Q：Redis 没起，会挂吗？**
不会。缓存读写都做了 try/catch，Redis 不可用时自动跳过缓存，主链路照常。

**Q：mock 模式推荐不准？**
正常——mock 向量只有字面相似度，且意图靠词典。它的使命是让你**零成本跑通并理解整条链路**；
配上真实 embedding 与 LLM 后（第 5 节），质量会跨档提升而代码不变。

**Q：怎么加自己的商品？**
往 `product` 表插数据（重点写好 `scene_tags` 和 `description`，这直接决定召回质量），
然后调 `POST /api/admin/reindex`。

---

*本 Demo 对应《AI智能商城_项目方案与实施计划》的 P1（纯文本 MVP）+ P2 部分（多模态框架）产出物，
评测脚手架（P4）与以图搜图召回通道是下一迭代的第一优先级。*
