# AI 智能商城（ai-mall）

> 企业福利 / 集采商城的**对话式 AI 导购**：用户用自然语言（可附图片）描述需求，系统理解意图、
> 从真实商品库中检索推荐、给出推荐理由，必要时反问澄清。
>
> 技术本质：**大模型 + RAG（检索增强）+ 导购对话** —— 理解交给大模型，找货交给向量检索，
> 排序交给业务规则，说话交给大模型，靠谱交给 RAG（只在真实召回结果里推荐，杜绝幻觉商品）。

**技术栈**：JDK 8 · Spring Boot 2.7.18 · MyBatis-Plus 3.5.7 · MySQL 8 · Redis · Lombok
**模型侧**：阿里百炼 OpenAI 兼容协议（意图/话术 = DeepSeek，视觉 = Qwen3-VL，向量 = text-embedding-v4）

---

## 目录

1. [系统架构与请求流程](#1-系统架构与请求流程)
2. [目录结构与各层关系](#2-目录结构与各层关系)
3. [从零开始在自己电脑上运行](#3-从零开始在自己电脑上运行)
4. [API 文档](#4-api-文档)
5. [核心机制说明](#6-核心机制说明)
6. [可扩展点](#7-可扩展点)
7. [常见问题排查](#8-常见问题排查)

---

## 1. 系统架构与请求流程

```
┌────────────────── 离线（启动时构建 / reindex 触发）──────────────────┐
│  product 表 → toEmbeddingText()语义文本 → EmbeddingClient → 内存向量库    │
│                     ↕ product_vector 表（向量持久化：启动零API调用、哈希增量） │
└──────────────────────────────────────────────────────────────┘
                                    │ 供数
在线（一次请求走完下面五步）：

 用户输入(文字/图片)
   │
   ▼
 ① RecommendPipeline 查 Redis 缓存 ──命中──► 直接返回(fromCache=true, 成本归零)
   │未命中
   ▼
 ② IntentService 意图理解        调用 LLM/VLM，输出结构化 JSON 槽位
   │                             （类目/预算/场景/关键词/B端条件/待澄清/图片描述）
   ▼
 ③ HybridRecallService 混合召回   SemanticRecallChannel(向量近邻) ∥ KeywordRecallChannel(LIKE)
   │                             → 按商品合并分数 → 硬过滤(库存>0/预算上限/类目)
   │                             → 空结果且有类目约束时自动放宽重试
   ▼
 ④ RerankService 精排             综合分 = 0.5语义 + 0.2关键词 + 0.15主推 + 0.15预算贴合(权重在yml)
   │                             → 取 top-5
   ▼
 ⑤ GenerationService 生成         LLM 生成导购话术(只能基于给定商品) + 构造商品卡
   │                             关键信息缺失 → 反问澄清
   ▼
 回填 Redis 缓存 → 返回 {reply, products[], intent, needClarification}
```

要点：
- **大模型只在两头**（②理解、⑤说话），中间的排序是可解释、可调参的规则公式——业务方对齐"什么排前面"靠改权重，不用发版；
- **③中的硬过滤是保险丝**：即使语义"觉得很像"，超预算/无库存的商品也绝不会出现在结果里；
- **⑤中商品卡由真实召回结果直接构造**，模型不决定"推荐谁"，只负责"怎么说"——从机制上杜绝编造商品。

---

## 2. 目录结构与各层关系

```
ai-mall/
├── pom.xml                        # Maven 构建：JDK8 + SB2.7 + MyBatis-Plus
├── .env                           # API Key 
├── .gitignore
├── sql/  
│    └── product.sql                    # 24条冒烟商品
│    └── product_vector.sql               # product_vector 建表  
└── src/main/
    ├── resources/
    │   ├── application.yml        # 全部可调配置：数据源/Redis/模型端点/召回参数/精排权重/缓存
    │   └── static/index.html      # 对话演示页（图片上传/商品卡/意图与得分调试信息）
    └── java/com/vetech/aimall/
        ├── AiMallApplication.java # 启动类，@MapperScan 扫描 mapper 包
        │
        ├── config/                # 【配置层】
        │   ├── AiMallProperties   #   aimall.* 配置总线，其他层通过它读配置
        │   └── RestTemplateConfig #   调用外部大模型的 HTTP 客户端
        │
        ├── controller/            # 【接口层】HTTP 入口，只做参数转换，不含业务
        │   ├── RecommendController#   /api/recommend(JSON) + /api/recommend/upload(带图)
        │   └── ProductController  #   /api/products + /api/admin/reindex
        │        │ 调用
        │        ▼
        ├── service/               # 【业务层】五步链路，每步一个独立 Service（便于按层归因调优）
        │   ├── RecommendPipeline  #   主编排：缓存→意图→召回→精排→生成
        │   ├── IntentService      #   ② 意图理解（调 llm 层）
        │   ├── recall/            #   ③ 混合召回
        │   │   ├── RecallChannel          # 召回通道SPI接口
        │   │   ├── SemanticRecallChannel  # 语义路（调 embedding + vectorstore + mapper 回库）
        │   │   ├── KeywordRecallChannel   # 关键词路（调 mapper 的 LIKE 查询）
        │   │   └── HybridRecallService    # 并联所有通道→合并→硬过滤→放宽重试
        │   ├── RerankService      #   ④ 精排（读 yml 权重）
        │   ├── GenerationService  #   ⑤ 话术生成（调 llm 层）+ 商品卡构造
        │   └── VectorIndexService #   离线侧：启动加载持久化向量 + 哈希增量同步（调 mapper + embedding + vectorstore）
        │        │ 依赖
        │        ▼
        ├── llm/                   # 【AI能力层-对话】LlmClient 接口 + OpenAI兼容实现 + 工厂(启动校验Key)
        ├── embedding/             # 【AI能力层-向量化】EmbeddingClient 接口 + OpenAI兼容实现 + 工厂
        ├── vectorstore/           # 【AI能力层-向量库】VectorStore 接口 + 内存实现 + VectorCodec编解码
        │        ▲
        │        │ 被 service 层通过接口调用（不感知具体实现，可插拔）
        │
        ├── mapper/                # 【数据访问层】MyBatis-Plus
        │   ├── ProductMapper      #   BaseMapper + @Select 关键词召回
        │   └── ProductVectorMapper#   BaseMapper（向量持久化表）
        │        │ 映射
        │        ▼
        └── model/                 # 【模型层】
            ├── entity/            #   Product / ProductVector（对应两张表）
            └── dto/               #   IntentResult / RecommendRequest / RecommendResponse / ProductCard / ScoredProduct
```

**层间依赖方向（单向，禁止反向）**：
`controller → service → (llm / embedding / vectorstore / mapper) → model`
service 层只依赖 llm/embedding/vectorstore 的**接口**，不感知具体实现——这是"换模型/换向量库不改业务代码"的根基。

---

## 3. 从零开始在自己电脑上运行

> 以下步骤在 Windows / macOS / Linux 均适用，差异处已分别标注。

### 第 0 步：前置软件检查

| 软件 | 版本要求 | 验证命令 |
|---|---|---|
| JDK | 8（1.8.x） | `java -version`（应显示 1.8.0_xxx） |
| Maven | 3.6+ | `mvn -v` |
| MySQL | 8.x | `mysql --version` |
| Redis | 5+ | `redis-cli ping`（应返回 PONG） |


没装的先装：
- **JDK 8**：Oracle JDK 8 或 Adoptium Temurin 8；装完配 JAVA_HOME。
- **MySQL 8**：官网 MySQL Installer（Windows）/ `brew install mysql`（macOS）/ `apt install mysql-server`（Ubuntu）。安装时设置好 **root 密码并记住**。
- **Redis**：
  - Windows：Redis 官方不出 Windows 版，推荐 [Memurai](https://www.memurai.com/)（Redis 兼容，装完即为服务）或微软 GitHub 上的 redis for windows 压缩包（解压后运行 `redis-server.exe`）；
  - macOS：`brew install redis && brew services start redis`；
  - Ubuntu：`apt install redis-server`。
  - 装完 `redis-cli ping` 返回 PONG 即可。**本项目 Redis 无密码、默认 6379**（有密码则在 application.yml 的 spring.redis 下补 password）。

### 第 1 步：初始化 MySQL（建库 + 建应用用户 + 建表）


```bash
先创建数据库ai_mall(utf8mb4_0900_ai_ci)
idea中导入两张表sql/product.sql和product_vector.sql，product中应该有1775条数据，
product_vector是空的，后面第一次向量化会把product中的数据向量化存入product_vector中。
```

### 第 2 步：确认 Redis 在跑

```bash
redis-cli ping     # 返回 PONG 即可
```

> Redis 只用于查询缓存。它挂了应用**仍能正常启动和推荐**（缓存读写有容错自动跳过），但建议开着——相同问题命中缓存时模型调用成本归零。

### 第 3 步：配置 API Key（.env 文件）

本项目通过 Spring Boot 的 `spring.config.import` 从项目根目录的 `.env` 文件读取密钥（见 application.yml 第一段），**不需要设置系统环境变量**：

编辑 `.env`，填入你的阿里百炼 API Key（两行填**同一个** Key 即可）：

```properties
AIMALL_LLM_API_KEY=sk-你的百炼Key
AIMALL_EMBEDDING_API_KEY=sk-你的百炼Key
```

Key 从哪来：登录 [阿里云百炼控制台](https://bailian.console.aliyun.com/) → API-KEY 管理 → 创建。**并做两件事**：
1. 在"模型广场"确认 application.yml 里三个模型串的**确切名字**（`chat-model` / `vision-model` / `embedding.model`）——名字差一个字符就报 model not found；托管的 DeepSeek 类模型可能需要点一次"开通"；
2. 确认账户有免费额度或余额。

> 安全提醒：`.env` 已在 .gitignore 中，**严禁**把 Key 写进 application.yml 或提交到任何仓库。

### 第 4 步：启动

```bash
mvn clean spring-boot:run
```

首次启动会发生什么（看日志确认）：
1. `LLM 接入: baseUrl=..., chatModel=...` —— Key 读到了（如果这里直接报错"缺少大模型 API Key"，回去检查第 3 步）；
2. `启动加载：从数据库载入 0 条已持久化向量` —— 第一次没有缓存向量；
3. `启动增量同步：新嵌 24，复用 0...` —— 24 条样例商品**真实调用 embedding** 并持久化（只花这一次钱）；
4. 之后每次重启：`载入 24 条 + 复用 24 + 新嵌 0` —— **零 API 调用**。

### 第 5 步：验证

浏览器打开 **http://localhost:8080**，在对话框输入：

> 想给团队买点夏天降暑的福利，预算50一个人

应看到：AI 生成的推荐话术 + 若干价格 ≤50 元的夏季商品卡（风扇/冰袖/水壶等）+ 底部的意图槽位调试信息。或用 curl：

```bash
curl -s -X POST http://localhost:8080/api/recommend \
  -H 'Content-Type: application/json' \
  -d '{"query":"有什么送客户的茶叶礼盒"}'
```

---


## 4. API 文档

### POST /api/recommend —— 推荐主接口（JSON）

请求：
```json
{
  "sessionId": "可选，多轮对话预留",
  "query": "想给团队买夏天降暑的福利，预算50一个人",
  "imageBase64": "可选，图片base64（不含 data: 前缀）",
  "imageMimeType": "image/jpeg"
}
```

响应：
```json
{
  "reply": "面向用户的导购话术（含推荐理由/反问澄清）",
  "products": [
    { "id": 4, "title": "桌面USB静音小风扇", "category": "小家电", "price": 45.00,
      "imageUrl": "/img/fan-usb.jpg", "sceneTags": "夏季,办公,员工福利,降暑",
      "pointsEligible": true, "stock": 600, "score": 0.83,
      "reason": "契合「夏季」场景，三档风力静音设计" }
  ],
  "intent": { "category": null, "budgetMax": 50, "keywords": ["降暑","福利"],
              "scenes": ["夏季","员工福利"], "attributes": {},
              "clarifications": ["团队人数"], "imageDescription": null },
  "needClarification": true,
  "fromCache": false
}
```

### POST /api/recommend/upload —— multipart 版（前端页面使用）
字段：`query`（文本）、`image`（图片文件，可选，≤10MB）。响应同上。

### GET /api/products —— 商品全量（调试/商品墙）

### POST /api/admin/reindex?force=false —— 向量索引增量同步
- 默认：只处理 新增/内容变化/换模型 的商品；
- `?force=true`：全量重嵌（改了 Product.toEmbeddingText 拼接逻辑本身时用）；
- 返回：`{"embedded":N, "skipped":N, "removed":N, "failed":N, "message":"..."}`。

---

## 5. 核心机制说明

### 5.1 向量持久化 + 哈希增量（为什么重启不花钱）
- 向量算好后存入 `product_vector` 表（float32 字节序 + 模型标签 + 内容哈希）；
- 启动先加载持久化向量（零 API 调用），再增量同步：对每个商品比对 `toEmbeddingText()` 的 SHA-256 与库中哈希——**只有新商品/描述变过/换过模型的才重新嵌入**；
- 换 embedding 模型（如 v3→v4、改维度）：模型标签变化自动触发一次全量重嵌，之后又回到零调用，无需任何手动操作；
- embedding 调用失败（返回全零）不落库不更新哈希，下次同步自动重试——不会把失败"缓存"成正确结果；
- 商品在 product 表里被删除：其向量作为"孤儿"在下次同步时从库和内存一并清理。

### 5.2 下架/无货商品为什么永远不会被推荐（惰性删除）
语义召回命中的只是 ID，取真实数据时回库 `selectById`（查不到=已删除，跳过），混合召回统一过滤 `stock<=0`。所以**下架只需把库存置 0**，不用动向量库。

### 5.3 容错设计
- 意图理解失败（超时/JSON 不合法）→ 构造"仅含原句关键词"的最简意图，召回仍可工作；
- query embedding 失败 → 本次跳过语义路，关键词召回兜底；
- 话术生成失败 → 回退清单式简版话术；
- Redis 不可用 → 跳过缓存；
- 以上全部打 warn 日志标记，**任何一环的模型抖动都不会导致 500**。

### 5.4 缓存
相同 query（MD5 为键）在 TTL 内直接返回缓存结果（fromCache=true），模型调用成本归零；带图请求不缓存。

---

## 6. 可扩展点

| # | 接口 | 扩展场景 | 动作 |
|---|---|---|---|
| 1 | `llm.LlmClient` | 换大模型厂商 / 拆 chat与vision 双端点 / 简单请求路由便宜模型 | OpenAI 兼容实现通吃主流厂商（改 yml）；拆端点改 Properties+Factory；路由在 Factory 包 Router |
| 2 | `embedding.EmbeddingClient` | 升级向量模型 / 图文融合向量(以图搜图) / 自托管 BGE | 改 yml；qwen3-vl-embedding 走 DashScope 原生 SDK 需新实现类 |
| 3 | `vectorstore.VectorStore` | SKU 过十万 / 多机部署 → Milvus/pgvector | 实现接口即可，上层召回代码零改动 |
| 4 | `service.recall.RecallChannel` | 以图搜图 / 协同过滤 / 运营置顶位 | 实现接口注册为 Bean **即自动**并入混合召回 |
| 5 | `service.RerankService` | rerank API(cross-encoder) / 个性化信号 | 综合分公式追加信号项 + yml 配权重 |

关键词召回升级：数据量大后把 `KeywordRecallChannel` 的 MySQL LIKE 换成 Elasticsearch（BM25），实现同一个 RecallChannel 接口即可。

---

## 7. 常见问题排查

| 症状 | 原因 | 解法 |
|---|---|---|
| 启动报"缺少大模型 API Key" | .env 没建 / 没放在项目根目录 / Key 行格式错 | 第 3 步重做；.env 必须与 pom.xml 同级；`KEY=value` 格式、无引号无空格 |
| 启动报数据库连接失败 | MySQL 没起 / init.sql 没执行 / 密码不匹配 | `mysql -u aimall -paimall123 ai_mall` 手工验证；确认 yml 与 init.sql 中账号密码一致 |
| 调用报 400 model not found | 模型串写错 | 去百炼"模型广场"复制确切模型串填入 yml 三处 |
| 调用报 401 / 权限 / 欠费 | Key 错 / 模型未开通 / 无余额 | 控制台逐项核对 Key、开通状态、余额 |
| 搜索总走"容错最简意图"（看 warn 日志） | LLM 持续调用失败 | 按上两条排查 Key/模型串/余额/网络 |
| 导入新商品后搜不到（或时有时无） | 忘了 reindex，语义路无向量而关键词路可搜 | `POST /api/admin/reindex` |
| Redis 连接失败告警刷屏 | Redis 没起 | `redis-cli ping`；不想用缓存可把 aimall.cache.enabled 设 false |
| Windows 跑 Python 脚本无输出秒退 | 用了 `python3`（命中商店空壳存根） | 用 `python`；必要时关闭 设置→应用→应用执行别名 里的 python3.exe |
| 数据生成脚本反复"本批解析为空" | 模型长输出格式坏掉，非调用失败 | 属已知现象会自动重试补足；可将脚本 BATCH 调小至 5、temperature 0.5、max_tokens 4096 |


